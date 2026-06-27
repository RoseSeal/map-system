from __future__ import annotations

import argparse
import asyncio
import csv
import importlib.metadata
import json
import os
import shutil
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import httpx

from graphrag_service.config import GraphRagConfig, load_config

from .baselines import (
    NAIVE_SYSTEM_PROMPT,
    NAIVE_USER_PROMPT_TEMPLATE,
    load_index_manifest,
    run_naive,
)
from .judge import GROUPS, JudgeBackend, JudgeError, judge_answers, preflight, resolve_backend, stable_hash


EVAL_DIR = Path(__file__).resolve().parent
QUERIES_PATH = EVAL_DIR / "queries.json"
RUBRIC_PATH = EVAL_DIR / "judge_prompt.txt"
RUNTIME_DIR = EVAL_DIR / "runtime"
RESULTS_DIR = EVAL_DIR / "results"
TOKEN_ESTIMATOR = "unicode-char-v1"
CSV_FIELDS = [
    "evaluation_run_id",
    "query_id",
    "category",
    "group",
    "comprehensiveness",
    "diversity",
    "empowerment",
    "latency_ms",
    "estimated_input_tokens",
    "estimated_output_tokens",
    "token_estimator",
    "retrieval_source",
    "judge_backend",
    "judge_model",
    "corpus_hash",
    "judged_at",
]


class RetrievalError(RuntimeError):
    pass


async def execute(
    *,
    pilot: bool,
    force_retrieval: bool,
    force_judge: bool,
    judge_backend_name: str,
    judge_model: str | None,
    sidecar_url: str | None,
    top_k: int | None,
    rerank_ablation: bool = False,
    original_run_id: str | None = None,
    skip_preflight: bool = False,
) -> str:
    queries = load_queries()
    selected_queries = [query for query in queries if query["pilot"]] if pilot else queries
    rubric = RUBRIC_PATH.read_text(encoding="utf-8")
    config = load_config()
    original_run_config: dict[str, Any] | None = None
    if rerank_ablation and not config.reranker_enabled:
        raise RuntimeError(
            "GRAPHRAG_RERANKER_ENABLED=true is required for --rerank-ablation"
        )
    if rerank_ablation:
        original_run_id = original_run_id or load_published_run_id()
        assert original_run_id is not None
        original_run_config = load_run_config(original_run_id)
        if (
            judge_backend_name == "gemini"
            and original_run_config.get("judge_backend") == "gemini"
            and shutil.which("agy")
        ):
            judge_backend_name = "agy"
        judge_model = judge_model or original_run_config["judge_model"]
        sidecar_url = sidecar_url or original_run_config["sidecar_url"]
        top_k = top_k or int(original_run_config["top_k"])
    else:
        sidecar_url = sidecar_url or "http://127.0.0.1:8100"
        top_k = top_k or 5
    manifest = load_index_manifest(config.working_dir)
    backend = resolve_backend(judge_backend_name, judge_model)
    if not skip_preflight:
        preflight(backend)
    run_config = build_run_config(
        queries=queries,
        rubric=rubric,
        manifest=manifest,
        config=config,
        backend=backend,
        sidecar_url=sidecar_url,
        top_k=top_k,
        rerank_ablation=rerank_ablation,
        original_run_id=original_run_id,
    )
    original_completed: dict[str, dict[str, Any]] = {}
    if rerank_ablation:
        assert original_run_id is not None
        validate_rerank_baseline(original_run_id, run_config)
        original_completed = load_original_completed(
            original_run_id,
            [query["query_id"] for query in selected_queries],
        )
    evaluation_run_id = stable_hash(run_config)
    run_dir = RUNTIME_DIR / "runs" / evaluation_run_id
    progress_path = run_dir / "progress.jsonl"
    completed = load_completed(progress_path)
    run_dir.mkdir(parents=True, exist_ok=True)
    _write_json_atomic(
        run_dir / "run_manifest.json",
        {"evaluation_run_id": evaluation_run_id, "run_config": run_config},
    )

    async with httpx.AsyncClient(timeout=180) as client:
        if rerank_ablation:
            await assert_sidecar_rerank(client, sidecar_url, config)
        for query in selected_queries:
            if (
                query["query_id"] in completed
                and not force_retrieval
                and not force_judge
            ):
                continue
            record = await evaluate_query(
                evaluation_run_id=evaluation_run_id,
                query=query,
                config=config,
                manifest=manifest,
                rubric=rubric,
                backend=backend,
                sidecar_url=sidecar_url,
                top_k=top_k,
                force_retrieval=force_retrieval,
                force_judge=force_judge,
                client=client,
                reuse_retrievals=(
                    {"naive": original_completed[query["query_id"]]["retrievals"]["naive"]}
                    if rerank_ablation
                    else None
                ),
            )
            append_progress(progress_path, record)
            if record["status"] == "completed":
                completed[query["query_id"]] = record
            else:
                completed.pop(query["query_id"], None)

    write_scores(run_dir / "scores.csv", completed.values())
    if not pilot and len(completed) == len(queries):
        publish_results(
            evaluation_run_id,
            run_config,
            completed,
            scores_name="scores_rerank.csv" if rerank_ablation else "scores.csv",
            manifest_name=(
                "run_manifest_rerank.json" if rerank_ablation else "run_manifest.json"
            ),
        )
    return evaluation_run_id


async def evaluate_query(
    *,
    evaluation_run_id: str,
    query: dict[str, Any],
    config: GraphRagConfig,
    manifest: dict[str, Any],
    rubric: str,
    backend: JudgeBackend,
    sidecar_url: str,
    top_k: int,
    force_retrieval: bool,
    force_judge: bool,
    client: httpx.AsyncClient,
    reuse_retrievals: dict[str, dict[str, Any]] | None = None,
) -> dict[str, Any]:
    try:
        retrievals = await retrieve_groups(
            query=query,
            config=config,
            manifest=manifest,
            sidecar_url=sidecar_url,
            top_k=top_k,
            force=force_retrieval,
            client=client,
            reuse_retrievals=reuse_retrievals,
        )
        judged = judge_answers(
            query=query,
            answers={group: retrievals[group]["answer"] for group in GROUPS},
            rubric=rubric,
            backend=backend,
            cache_dir=RUNTIME_DIR / "cache" / "judge",
            force=force_judge,
        )
        return completed_record(
            evaluation_run_id=evaluation_run_id,
            query=query,
            retrievals=retrievals,
            judged=judged,
            backend=backend,
            corpus_hash=manifest["corpus_hash"],
        )
    except RetrievalError as exc:
        return _failure_record(query["query_id"], "retrieval_failed", exc)
    except JudgeError as exc:
        return _failure_record(query["query_id"], "judge_failed", exc)


async def retrieve_groups(
    *,
    query: dict[str, Any],
    config: GraphRagConfig,
    manifest: dict[str, Any],
    sidecar_url: str,
    top_k: int,
    force: bool,
    client: httpx.AsyncClient,
    reuse_retrievals: dict[str, dict[str, Any]] | None = None,
) -> dict[str, dict[str, Any]]:
    reuse_retrievals = reuse_retrievals or {}

    async def fetch_group(group: str) -> tuple[str, dict[str, Any]]:
        if group in reuse_retrievals:
            return group, reuse_retrievals[group]
        fingerprint = retrieval_fingerprint(
            query=query,
            group=group,
            config=config,
            manifest=manifest,
            sidecar_url=sidecar_url,
            top_k=top_k,
        )
        cache_path = RUNTIME_DIR / "cache" / "retrieval" / fingerprint / "result.json"
        if cache_path.exists() and not force:
            return group, json.loads(cache_path.read_text(encoding="utf-8"))
        try:
            if group == "naive":
                result = await run_naive(
                    query=query["query"],
                    situation=query["situation"],
                    top_k=top_k,
                    config=config,
                )
            else:
                response = await client.post(
                    sidecar_url.rstrip("/") + "/retrieve",
                    json={
                        "query": query["query"],
                        "situation": query["situation"],
                        "mode": group,
                        "top_k": top_k,
                    },
                )
                response.raise_for_status()
                result = response.json()
        except httpx.HTTPError as exc:
            raise RetrievalError(f"{group} retrieval failed: {exc}") from exc
        _write_json_atomic(cache_path, result)
        return group, result

    group_results = await asyncio.gather(
        *(fetch_group(group) for group in GROUPS),
        return_exceptions=True,
    )
    results: dict[str, dict[str, Any]] = {}
    first_error: Exception | None = None
    for item in group_results:
        if isinstance(item, Exception):
            first_error = first_error or item
            continue
        group, result = item
        results[group] = result
    if first_error is not None:
        raise first_error
    return results


def build_run_config(
    *,
    queries: list[dict[str, Any]],
    rubric: str,
    manifest: dict[str, Any],
    config: GraphRagConfig,
    backend: JudgeBackend,
    sidecar_url: str,
    top_k: int,
    rerank_ablation: bool = False,
    original_run_id: str | None = None,
) -> dict[str, Any]:
    reranker_payload = reranker_config_payload(config)
    return {
        "queries_hash": stable_hash(queries),
        "corpus_hash": manifest["corpus_hash"],
        "index_built_at": manifest.get("built_at"),
        "lightrag_version": _package_version("lightrag-hku"),
        "top_k": top_k,
        "sidecar_url": sidecar_url.rstrip("/"),
        "llm_model": config.llm_model,
        "embedding_model": config.embed_model,
        "llm_base_url": config.llm_base_url,
        "reranker_enabled": config.reranker_enabled,
        "reranker_model": config.reranker_model if config.reranker_enabled else None,
        "reranker_backend": config.reranker_backend if config.reranker_enabled else None,
        "reranker_min_score": (
            config.reranker_min_score if config.reranker_enabled else None
        ),
        "reranker_config_hash": stable_hash(reranker_payload),
        "rerank_ablation": rerank_ablation,
        "baseline_evaluation_run_id": original_run_id if rerank_ablation else None,
        "naive_generation_prompt_hash": stable_hash(
            {
                "system": NAIVE_SYSTEM_PROMPT,
                "user_template": NAIVE_USER_PROMPT_TEMPLATE,
            }
        ),
        "rubric_hash": stable_hash(rubric),
        "judge_backend": backend.name,
        "judge_model": backend.model,
        "judge_cli": backend.name,
        "judge_cli_version": backend.version,
        "token_estimator": TOKEN_ESTIMATOR,
    }


def retrieval_fingerprint(
    *,
    query: dict[str, Any],
    group: str,
    config: GraphRagConfig,
    manifest: dict[str, Any],
    sidecar_url: str,
    top_k: int,
) -> str:
    reranker_payload = (
        reranker_config_payload(config)
        if group != "naive" and config.reranker_enabled
        else {"enabled": False}
    )
    return stable_hash(
        {
            "query": query,
            "group": group,
            "corpus_hash": manifest["corpus_hash"],
            "index_built_at": manifest.get("built_at"),
            "lightrag_version": _package_version("lightrag-hku"),
            "top_k": top_k,
            "sidecar_url": sidecar_url.rstrip("/"),
            "llm_model": config.llm_model,
            "embedding_model": config.embed_model,
            "llm_base_url": config.llm_base_url,
            "reranker_enabled": reranker_payload["enabled"],
            "reranker_model": reranker_payload.get("model"),
            "reranker_config_hash": stable_hash(reranker_payload),
            "naive_generation_prompt": (
                {
                    "system": NAIVE_SYSTEM_PROMPT,
                    "user_template": NAIVE_USER_PROMPT_TEMPLATE,
                }
                if group == "naive"
                else None
            ),
        }
    )


def completed_record(
    *,
    evaluation_run_id: str,
    query: dict[str, Any],
    retrievals: dict[str, dict[str, Any]],
    judged: dict[str, Any],
    backend: JudgeBackend,
    corpus_hash: str,
) -> dict[str, Any]:
    judged_at = judged["judged_at"]
    rows = []
    for group in GROUPS:
        retrieval = retrievals[group]
        scores = judged["scores"][group]
        input_text = (
            retrieval.get("_estimated_input_text")
            if group == "naive"
            else retrieval.get("query_effective", "")
        )
        rows.append(
            {
                "evaluation_run_id": evaluation_run_id,
                "query_id": query["query_id"],
                "category": query["category"],
                "group": group,
                "comprehensiveness": scores["comprehensiveness"],
                "diversity": scores["diversity"],
                "empowerment": scores["empowerment"],
                "latency_ms": retrieval["metrics"]["latency_ms"],
                "estimated_input_tokens": estimate_tokens(input_text or ""),
                "estimated_output_tokens": estimate_tokens(retrieval["answer"]),
                "token_estimator": TOKEN_ESTIMATOR,
                "retrieval_source": retrieval["metrics"]["retrieval_source"],
                "judge_backend": backend.name,
                "judge_model": backend.model,
                "corpus_hash": corpus_hash,
                "judged_at": judged_at,
            }
        )
    return {
        "query_id": query["query_id"],
        "status": "completed",
        "query": query,
        "retrievals": retrievals,
        "judge": judged,
        "rows": rows,
        "recorded_at": judged_at,
    }


def reranker_config_payload(config: GraphRagConfig) -> dict[str, Any]:
    if not config.reranker_enabled:
        return {"enabled": False}
    return {
        "enabled": True,
        "backend": config.reranker_backend,
        "model": config.reranker_model,
        "min_score": config.reranker_min_score,
    }


def load_published_run_id() -> str:
    manifest_path = RESULTS_DIR / "run_manifest.json"
    if not manifest_path.exists():
        raise RuntimeError(
            "--rerank-ablation requires --original-run-id or results/run_manifest.json"
        )
    payload = json.loads(manifest_path.read_text(encoding="utf-8"))
    run_id = payload.get("evaluation_run_id")
    if not run_id:
        raise RuntimeError(f"{manifest_path} does not contain evaluation_run_id")
    return str(run_id)


def load_run_config(run_id: str) -> dict[str, Any]:
    manifest_path = RUNTIME_DIR / "runs" / run_id / "run_manifest.json"
    if not manifest_path.exists():
        raise RuntimeError(f"original run manifest not found: {manifest_path}")
    payload = json.loads(manifest_path.read_text(encoding="utf-8"))
    run_config = payload.get("run_config")
    if not isinstance(run_config, dict):
        raise RuntimeError(f"{manifest_path} does not contain run_config")
    return run_config


def load_original_completed(
    original_run_id: str,
    query_ids: list[str],
) -> dict[str, dict[str, Any]]:
    progress_path = RUNTIME_DIR / "runs" / original_run_id / "progress.jsonl"
    completed = load_completed(progress_path)
    missing = [
        query_id
        for query_id in query_ids
        if query_id not in completed
        or "naive" not in completed[query_id].get("retrievals", {})
    ]
    if missing:
        raise RuntimeError(
            f"original run {original_run_id} is missing naive retrievals for {missing}"
        )
    return completed


def validate_rerank_baseline(
    original_run_id: str,
    rerank_run_config: dict[str, Any],
) -> None:
    original = load_run_config(original_run_id)
    allow_agy_migration = (
        original.get("judge_backend") == "gemini"
        and rerank_run_config.get("judge_backend") == "agy"
        and original.get("judge_model") == rerank_run_config.get("judge_model")
    )
    controlled_keys = (
        "queries_hash",
        "corpus_hash",
        "index_built_at",
        "lightrag_version",
        "top_k",
        "sidecar_url",
        "llm_model",
        "embedding_model",
        "llm_base_url",
        "naive_generation_prompt_hash",
        "rubric_hash",
        "judge_model",
        "token_estimator",
    )
    mismatches = [
        key
        for key in controlled_keys
        if original.get(key) != rerank_run_config.get(key)
    ]
    if not allow_agy_migration:
        for key in ("judge_backend", "judge_cli", "judge_cli_version"):
            if original.get(key) != rerank_run_config.get(key):
                mismatches.append(key)
    if mismatches:
        details = {
            key: {
                "original": original.get(key),
                "rerank": rerank_run_config.get(key),
            }
            for key in mismatches
        }
        raise RuntimeError(
            "rerank ablation must keep the original run config unchanged: "
            + json.dumps(details, ensure_ascii=False, sort_keys=True)
        )


async def assert_sidecar_rerank(
    client: httpx.AsyncClient,
    sidecar_url: str,
    config: GraphRagConfig,
) -> None:
    health_url = sidecar_url.rstrip("/") + "/health"
    try:
        response = await client.get(health_url)
        response.raise_for_status()
    except httpx.HTTPError as exc:
        raise RuntimeError(f"sidecar health check failed: {health_url}") from exc
    payload = response.json()
    if payload.get("reranker_enabled") is not True:
        raise RuntimeError("sidecar is not running with GRAPHRAG_RERANKER_ENABLED=true")
    if payload.get("reranker_model") != config.reranker_model:
        raise RuntimeError(
            "sidecar reranker model does not match eval config: "
            f"{payload.get('reranker_model')} != {config.reranker_model}"
        )


def load_queries() -> list[dict[str, Any]]:
    payload = json.loads(QUERIES_PATH.read_text(encoding="utf-8"))
    if not isinstance(payload, list):
        raise ValueError("queries.json root must be an array")
    return payload


def load_completed(progress_path: Path) -> dict[str, dict[str, Any]]:
    completed: dict[str, dict[str, Any]] = {}
    if not progress_path.exists():
        return completed
    for line in progress_path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        record = json.loads(line)
        query_id = record.get("query_id")
        if record.get("status") == "completed":
            completed[query_id] = record
        elif query_id:
            completed.pop(query_id, None)
    return completed


def append_progress(path: Path, record: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(record, ensure_ascii=False) + "\n")


def write_scores(path: Path, records: Any) -> None:
    rows = [
        row
        for record in sorted(records, key=lambda item: item["query_id"])
        for row in record["rows"]
    ]
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=CSV_FIELDS)
        writer.writeheader()
        writer.writerows(rows)
    temporary.replace(path)


def publish_results(
    evaluation_run_id: str,
    run_config: dict[str, Any],
    completed: dict[str, dict[str, Any]],
    *,
    scores_name: str = "scores.csv",
    manifest_name: str = "run_manifest.json",
) -> None:
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    write_scores(RESULTS_DIR / scores_name, completed.values())
    _write_json_atomic(
        RESULTS_DIR / manifest_name,
        {"evaluation_run_id": evaluation_run_id, "run_config": run_config},
    )


def estimate_tokens(text: str) -> int:
    return len(text)


def _failure_record(query_id: str, status: str, exc: Exception) -> dict[str, Any]:
    return {
        "query_id": query_id,
        "status": status,
        "error": str(exc),
        "recorded_at": _utc_now(),
    }


def _package_version(name: str) -> str:
    try:
        return importlib.metadata.version(name)
    except importlib.metadata.PackageNotFoundError:
        return "not-installed"


def _write_json_atomic(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the GraphRAG retrieval evaluation.")
    parser.add_argument("--pilot", action="store_true", help="Run one query from each A-D category.")
    parser.add_argument("--force-retrieval", action="store_true")
    parser.add_argument("--force-judge", action="store_true")
    parser.add_argument(
        "--judge-backend",
        choices=("agy", "gemini", "codex", "copilot", "claude"),
        default=os.getenv("EVAL_JUDGE_BACKEND", "gemini"),
    )
    parser.add_argument("--judge-model", default=os.getenv("EVAL_JUDGE_MODEL"))
    parser.add_argument(
        "--sidecar-url",
        default=os.getenv("EVAL_SIDECAR_URL"),
    )
    parser.add_argument("--top-k", type=int)
    parser.add_argument(
        "--rerank-ablation",
        action="store_true",
        help="Run the controlled reranker ablation and publish scores_rerank.csv.",
    )
    parser.add_argument(
        "--original-run-id",
        default=os.getenv("EVAL_ORIGINAL_RUN_ID"),
        help="Original no-rerank run id whose naive retrievals are reused.",
    )
    args = parser.parse_args()
    if args.top_k is not None and not 1 <= args.top_k <= 10:
        parser.error("--top-k must be between 1 and 10")
    run_id = asyncio.run(
        execute(
            pilot=args.pilot,
            force_retrieval=args.force_retrieval,
            force_judge=args.force_judge,
            judge_backend_name=args.judge_backend,
            judge_model=args.judge_model,
            sidecar_url=args.sidecar_url,
            top_k=args.top_k,
            rerank_ablation=args.rerank_ablation,
            original_run_id=args.original_run_id,
        )
    )
    print(f"Evaluation run: {run_id}")


if __name__ == "__main__":
    main()
