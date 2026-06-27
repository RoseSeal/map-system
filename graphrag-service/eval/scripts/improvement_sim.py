from __future__ import annotations

import argparse
import csv
import importlib.util
import json
import os
import shutil
import subprocess
import urllib.error
import urllib.request
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from statistics import mean
from typing import Any


GROUPS = ("naive", "local", "global", "hybrid")
GRAPH_GROUPS = ("local", "global", "hybrid")
METRICS = ("comprehensiveness", "diversity", "empowerment")
EXPECTED_MEANS = {
    "naive": 8.3542,
    "local": 5.7917,
    "hybrid": 4.9167,
    "global": 4.6250,
}


@dataclass(frozen=True)
class ScoreRow:
    evaluation_run_id: str
    query_id: str
    category: str
    group: str
    comprehensiveness: float
    diversity: float
    empowerment: float
    latency_ms: float
    retrieval_source: str

    @property
    def quality(self) -> float:
        return mean((self.comprehensiveness, self.diversity, self.empowerment))

    @property
    def latency_s(self) -> float:
        return self.latency_ms / 1000


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Run post-hoc routing and abstention simulations for the GraphRAG evaluation."
    )
    eval_dir = Path(__file__).resolve().parents[1]
    parser.add_argument("--scores", type=Path, default=eval_dir / "results" / "scores.csv")
    parser.add_argument(
        "--manifest",
        type=Path,
        default=eval_dir / "results" / "run_manifest.json",
    )
    parser.add_argument(
        "--rerank-scores",
        type=Path,
        default=eval_dir / "results" / "scores_rerank.csv",
    )
    parser.add_argument(
        "--rerank-baseline-scores",
        type=Path,
        help=(
            "Optional no-rerank scores file scored with the same judge backend as "
            "scores_rerank.csv. Defaults to results/scores_no_rerank_agy.csv when present."
        ),
    )
    parser.add_argument("--results-dir", type=Path, default=eval_dir / "results")
    parser.add_argument("--plots-dir", type=Path, default=eval_dir / "plots")
    parser.add_argument(
        "--check-judge-preflight",
        action="store_true",
        help="Call the configured judge CLI with a tiny prompt for rerank preflight.",
    )
    parser.add_argument(
        "--paper-figures-dir",
        type=Path,
        help="Optional graph-rag-report figure directory to receive copies of final PNGs.",
    )
    args = parser.parse_args()

    rows = load_scores(args.scores)
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    validate_scores(rows, check_expected_means=True)
    rerank_baseline_scores = (
        args.rerank_baseline_scores
        or default_rerank_baseline_scores(args.results_dir, args.scores)
    )
    rerank_base_rows = load_scores(rerank_baseline_scores)
    validate_scores(rerank_base_rows, check_expected_means=False)

    args.results_dir.mkdir(parents=True, exist_ok=True)
    args.plots_dir.mkdir(parents=True, exist_ok=True)

    routing_rows, route_details = build_routing_rows(rows)
    gating_rows = build_gating_rows(rows)
    rerank_rows, rerank_summary = build_rerank_rows(
        rerank_base_rows,
        args.rerank_scores,
        rerank_baseline_scores,
        eval_dir,
    )
    rerank_preflight = build_rerank_preflight(
        args.rerank_scores,
        args.manifest,
        check_judge_preflight=args.check_judge_preflight,
    )

    write_csv(args.results_dir / "improvement_routing.csv", routing_rows)
    write_csv(args.results_dir / "improvement_gating.csv", gating_rows)
    if rerank_rows:
        write_csv(args.results_dir / "improvement_rerank.csv", rerank_rows)
    (args.results_dir / "rerank_preflight.json").write_text(
        json.dumps(rerank_preflight, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    plot_routing(routing_rows, args.plots_dir / "routing_policies.png")
    plot_gating(gating_rows, args.plots_dir / "gating_sensitivity.png")
    plot_rerank(
        rerank_rows,
        rerank_base_rows,
        args.plots_dir / "rerank_ablation.png",
        available=bool(rerank_rows),
    )

    report = render_report(
        rows=rows,
        manifest=manifest,
        routing_rows=routing_rows,
        gating_rows=gating_rows,
        route_details=route_details,
        rerank_rows=rerank_rows,
        rerank_summary=rerank_summary,
        rerank_preflight=rerank_preflight,
    )
    (args.results_dir / "improvement_results.md").write_text(report, encoding="utf-8")

    if args.paper_figures_dir:
        args.paper_figures_dir.mkdir(parents=True, exist_ok=True)
        for name in (
            "routing_policies.png",
            "gating_sensitivity.png",
            "rerank_ablation.png",
        ):
            shutil.copy2(args.plots_dir / name, args.paper_figures_dir / name)

    print(f"Wrote improvement outputs under {args.results_dir} and {args.plots_dir}")


def load_scores(path: Path) -> list[ScoreRow]:
    with path.open(encoding="utf-8", newline="") as stream:
        raw_rows = list(csv.DictReader(stream))
    required = {
        "evaluation_run_id",
        "query_id",
        "category",
        "group",
        "comprehensiveness",
        "diversity",
        "empowerment",
        "latency_ms",
        "retrieval_source",
    }
    if not raw_rows:
        raise ValueError(f"{path}: no rows")
    missing = required.difference(raw_rows[0])
    if missing:
        raise ValueError(f"{path}: missing columns {sorted(missing)}")
    return [
        ScoreRow(
            evaluation_run_id=row["evaluation_run_id"],
            query_id=row["query_id"],
            category=row["category"],
            group=row["group"],
            comprehensiveness=float(row["comprehensiveness"]),
            diversity=float(row["diversity"]),
            empowerment=float(row["empowerment"]),
            latency_ms=float(row["latency_ms"]),
            retrieval_source=row["retrieval_source"],
        )
        for row in raw_rows
    ]


def validate_scores(rows: list[ScoreRow], *, check_expected_means: bool) -> None:
    if len(rows) != 64:
        raise ValueError(f"expected 64 score rows, got {len(rows)}")
    groups = Counter(row.group for row in rows)
    if groups != Counter({group: 16 for group in GROUPS}):
        raise ValueError(f"unexpected group counts: {groups}")
    categories = Counter(row.category for row in rows)
    if categories != Counter({"A": 16, "B": 16, "C": 16, "D": 16}):
        raise ValueError(f"unexpected category-row counts: {categories}")
    by_query = defaultdict(set)
    for row in rows:
        by_query[row.query_id].add(row.group)
    incomplete = {
        query_id: sorted(groups_for_query)
        for query_id, groups_for_query in by_query.items()
        if set(groups_for_query) != set(GROUPS)
    }
    if len(by_query) != 16 or incomplete:
        raise ValueError(f"incomplete query coverage: {incomplete}")
    if check_expected_means:
        for group, expected in EXPECTED_MEANS.items():
            actual = round(mean(row.quality for row in rows if row.group == group), 4)
            if abs(actual - expected) > 0.0001:
                raise ValueError(f"{group} mean mismatch: expected {expected}, got {actual}")


def build_routing_rows(rows: list[ScoreRow]) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    by_query = rows_by_query(rows)
    naive_overall = mean(row.quality for row in rows if row.group == "naive")
    routing_rows: list[dict[str, Any]] = []
    route_details: dict[str, Any] = {}

    strategies = {
        **{f"always-{group}": fixed_strategy(group) for group in GROUPS},
        "oracle": oracle_strategy(include_naive=True),
        "oracle-exclude-naive": oracle_strategy(include_naive=False),
        "category-rule-in-sample": category_rule_in_sample(rows),
        "category-rule-loocv": category_rule_loocv(rows),
    }

    for strategy_name, selector in strategies.items():
        selected = [selector(query_id, by_query[query_id]) for query_id in sorted(by_query)]
        routing_rows.append(summarize_selection("overall", "ALL", strategy_name, selected, naive_overall))
        for category in ("A", "B", "C", "D"):
            category_selected = [row for row in selected if row.category == category]
            category_naive = mean(
                row.quality for row in rows if row.category == category and row.group == "naive"
            )
            routing_rows.append(
                summarize_selection(
                    "category",
                    category,
                    strategy_name,
                    category_selected,
                    category_naive,
                )
            )

    oracle_selected = [strategies["oracle"](query_id, by_query[query_id]) for query_id in sorted(by_query)]
    route_details["oracle_non_naive_wins"] = [
        {
            "query_id": row.query_id,
            "category": row.category,
            "selected_group": row.group,
            "selected_quality": round(row.quality, 4),
            "naive_quality": round(by_query[row.query_id]["naive"].quality, 4),
            "delta_vs_naive": round(row.quality - by_query[row.query_id]["naive"].quality, 4),
        }
        for row in oracle_selected
        if row.group != "naive"
    ]
    route_details["category_rule"] = {
        category: category_best_group(rows, category)
        for category in ("A", "B", "C", "D")
    }
    route_details["loocv_choices"] = {
        query_id: strategies["category-rule-loocv"](query_id, by_query[query_id]).group
        for query_id in sorted(by_query)
    }
    return routing_rows, route_details


def rows_by_query(rows: list[ScoreRow]) -> dict[str, dict[str, ScoreRow]]:
    result: dict[str, dict[str, ScoreRow]] = defaultdict(dict)
    for row in rows:
        result[row.query_id][row.group] = row
    return dict(result)


def fixed_strategy(group: str):
    def select(_query_id: str, query_rows: dict[str, ScoreRow]) -> ScoreRow:
        return query_rows[group]

    return select


def oracle_strategy(*, include_naive: bool):
    allowed = set(GROUPS if include_naive else GRAPH_GROUPS)

    def select(_query_id: str, query_rows: dict[str, ScoreRow]) -> ScoreRow:
        return max(
            (row for group, row in query_rows.items() if group in allowed),
            key=lambda row: (row.quality, -row.latency_ms, row.group),
        )

    return select


def category_rule_in_sample(rows: list[ScoreRow]):
    rule = {category: category_best_group(rows, category) for category in ("A", "B", "C", "D")}

    def select(_query_id: str, query_rows: dict[str, ScoreRow]) -> ScoreRow:
        category = next(iter(query_rows.values())).category
        return query_rows[rule[category]]

    return select


def category_rule_loocv(rows: list[ScoreRow]):
    by_query = rows_by_query(rows)

    def select(query_id: str, query_rows: dict[str, ScoreRow]) -> ScoreRow:
        category = next(iter(query_rows.values())).category
        means = {}
        latency = {}
        for group in GROUPS:
            values = [
                row.quality
                for row in rows
                if row.category == category and row.group == group and row.query_id != query_id
            ]
            means[group] = mean(values)
            latency[group] = query_rows[group].latency_ms
        selected_group = max(GROUPS, key=lambda group: (means[group], -latency[group]))
        return query_rows[selected_group]

    return select


def category_best_group(rows: list[ScoreRow], category: str) -> str:
    means = {
        group: mean(row.quality for row in rows if row.category == category and row.group == group)
        for group in GROUPS
    }
    return max(GROUPS, key=lambda group: (means[group], -mean(row.latency_ms for row in rows if row.group == group)))


def summarize_selection(
    scope: str,
    scope_value: str,
    strategy: str,
    selected: list[ScoreRow],
    baseline_quality: float,
) -> dict[str, Any]:
    selected_counts = Counter(row.group for row in selected)
    return {
        "scope": scope,
        "scope_value": scope_value,
        "strategy": strategy,
        "mean_quality": round(mean(row.quality for row in selected), 4),
        "delta_vs_naive": round(mean(row.quality for row in selected) - baseline_quality, 4),
        "mean_latency_s": round(mean(row.latency_s for row in selected), 4),
        "selected_naive": selected_counts["naive"],
        "selected_local": selected_counts["local"],
        "selected_global": selected_counts["global"],
        "selected_hybrid": selected_counts["hybrid"],
    }


def build_gating_rows(rows: list[ScoreRow]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for group in GRAPH_GROUPS:
        d_rows = [row for row in rows if row.category == "D" and row.group == group]
        non_d_rows = [row for row in rows if row.category != "D" and row.group == group]
        base_d = mean(row.quality for row in d_rows)
        base_all = mean(row.quality for row in rows if row.group == group)
        for assumed_score in range(3, 9):
            gated_d = float(assumed_score)
            gated_all = mean([row.quality for row in non_d_rows] + [gated_d] * len(d_rows))
            result.append(
                {
                    "branch": "B_category_proxy",
                    "scope": "D_only",
                    "group": group,
                    "assumed_abstain_score": assumed_score,
                    "baseline_quality": round(base_d, 4),
                    "gated_quality": round(gated_d, 4),
                    "delta": round(gated_d - base_d, 4),
                    "note": "All D-category graph answers are replaced by the assumed abstention score.",
                }
            )
            result.append(
                {
                    "branch": "B_category_proxy",
                    "scope": "all_queries",
                    "group": group,
                    "assumed_abstain_score": assumed_score,
                    "baseline_quality": round(base_all, 4),
                    "gated_quality": round(gated_all, 4),
                    "delta": round(gated_all - base_all, 4),
                    "note": "Only D-category graph answers are replaced; A-C scores remain unchanged.",
                }
            )
    graph_d_rows = [row for row in rows if row.category == "D" and row.group in GRAPH_GROUPS]
    base_graph_d = mean(row.quality for row in graph_d_rows)
    for assumed_score in range(3, 9):
        result.append(
            {
                "branch": "B_category_proxy",
                "scope": "D_graph_mean",
                "group": "graph_modes",
                "assumed_abstain_score": assumed_score,
                "baseline_quality": round(base_graph_d, 4),
                "gated_quality": round(float(assumed_score), 4),
                "delta": round(float(assumed_score) - base_graph_d, 4),
                "note": "Mean over local/global/hybrid D-category rows.",
            }
        )
    return result


def build_rerank_rows(
    base_rows: list[ScoreRow],
    rerank_scores_path: Path,
    baseline_scores_path: Path,
    eval_dir: Path,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    if not rerank_scores_path.exists():
        return [], {
            "available": False,
            "path": str(rerank_scores_path),
            "baseline_path": str(baseline_scores_path),
            "runtime_summary": {"available": False},
        }
    rerank_rows = load_scores(rerank_scores_path)
    base_by_group = {group: [row for row in base_rows if row.group == group] for group in GROUPS}
    rerank_by_group = {group: [row for row in rerank_rows if row.group == group] for group in GROUPS}
    output: list[dict[str, Any]] = []
    for group in GROUPS:
        if not rerank_by_group[group]:
            continue
        base_mean = mean(row.quality for row in base_by_group[group])
        rerank_mean = mean(row.quality for row in rerank_by_group[group])
        output.append(
            {
                "scope": "overall",
                "scope_value": "ALL",
                "group": group,
                "without_rerank_quality": round(base_mean, 4),
                "with_rerank_quality": round(rerank_mean, 4),
                "delta": round(rerank_mean - base_mean, 4),
                "without_rerank_latency_s": round(mean(row.latency_s for row in base_by_group[group]), 4),
                "with_rerank_latency_s": round(mean(row.latency_s for row in rerank_by_group[group]), 4),
            }
        )
        for category in ("A", "B", "C", "D"):
            base_category = [row for row in base_by_group[group] if row.category == category]
            rerank_category = [row for row in rerank_by_group[group] if row.category == category]
            if not base_category or not rerank_category:
                continue
            base_mean = mean(row.quality for row in base_category)
            rerank_mean = mean(row.quality for row in rerank_category)
            output.append(
                {
                    "scope": "category",
                    "scope_value": category,
                    "group": group,
                    "without_rerank_quality": round(base_mean, 4),
                    "with_rerank_quality": round(rerank_mean, 4),
                    "delta": round(rerank_mean - base_mean, 4),
                    "without_rerank_latency_s": round(mean(row.latency_s for row in base_category), 4),
                    "with_rerank_latency_s": round(mean(row.latency_s for row in rerank_category), 4),
                }
            )
    return output, {
        "available": True,
        "path": str(rerank_scores_path),
        "baseline_path": str(baseline_scores_path),
        "runtime_summary": build_rerank_runtime_summary(base_rows, rerank_rows, eval_dir),
    }


def build_rerank_runtime_summary(
    base_rows: list[ScoreRow],
    rerank_rows: list[ScoreRow],
    eval_dir: Path,
) -> dict[str, Any]:
    base_run_id = base_rows[0].evaluation_run_id if base_rows else None
    rerank_run_id = rerank_rows[0].evaluation_run_id if rerank_rows else None
    if not base_run_id or not rerank_run_id:
        return {"available": False, "reason": "missing_run_id"}

    base_records = load_progress_records(eval_dir / "runtime" / "runs" / base_run_id / "progress.jsonl")
    rerank_records = load_progress_records(eval_dir / "runtime" / "runs" / rerank_run_id / "progress.jsonl")
    if not base_records or not rerank_records:
        return {
            "available": False,
            "reason": "missing_progress_records",
            "baseline_run_id": base_run_id,
            "rerank_run_id": rerank_run_id,
        }

    by_group: dict[str, dict[str, int]] = {}
    for group in GRAPH_GROUPS:
        total = 0
        same_answer = 0
        same_case_order = 0
        same_case_set = 0
        for query_id, base_record in sorted(base_records.items()):
            rerank_record = rerank_records.get(query_id)
            if not rerank_record:
                continue
            base_retrieval = base_record.get("retrievals", {}).get(group, {})
            rerank_retrieval = rerank_record.get("retrievals", {}).get(group, {})
            base_cases = [case.get("case_id") for case in base_retrieval.get("cases", [])]
            rerank_cases = [case.get("case_id") for case in rerank_retrieval.get("cases", [])]
            total += 1
            if base_retrieval.get("answer") == rerank_retrieval.get("answer"):
                same_answer += 1
            if base_cases == rerank_cases:
                same_case_order += 1
            if set(base_cases) == set(rerank_cases):
                same_case_set += 1
        by_group[group] = {
            "total": total,
            "same_answer": same_answer,
            "answer_changed": total - same_answer,
            "case_order_changed": total - same_case_order,
            "case_set_changed": total - same_case_set,
        }

    total_answers = sum(item["total"] for item in by_group.values())
    answer_changed = sum(item["answer_changed"] for item in by_group.values())
    return {
        "available": True,
        "baseline_run_id": base_run_id,
        "rerank_run_id": rerank_run_id,
        "total_graph_answers": total_answers,
        "answer_changed": answer_changed,
        "all_graph_answers_unchanged": answer_changed == 0 and total_answers > 0,
        "groups": by_group,
    }


def load_progress_records(path: Path) -> dict[str, dict[str, Any]]:
    if not path.exists():
        return {}
    records: dict[str, dict[str, Any]] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        record = json.loads(line)
        if record.get("status") == "completed":
            records[record["query_id"]] = record
    return records


def default_rerank_baseline_scores(results_dir: Path, fallback_scores: Path) -> Path:
    same_judge_baseline = results_dir / "scores_no_rerank_agy.csv"
    if same_judge_baseline.exists():
        return same_judge_baseline
    return fallback_scores


def build_rerank_preflight(
    rerank_scores_path: Path,
    manifest_path: Path,
    *,
    check_judge_preflight: bool = False,
) -> dict[str, Any]:
    required_env = ("GRAPHRAG_LLM_API_KEY",)
    env_status = {name: bool(os.getenv(name)) for name in required_env}
    judge_model = resolve_original_judge_model(manifest_path)
    judge_status = {
        "env_set": bool(os.getenv("EVAL_JUDGE_MODEL")),
        "original_manifest_model": judge_model,
        "resolved": os.getenv("EVAL_JUDGE_MODEL") or judge_model,
    }
    sidecar = check_sidecar_health("http://127.0.0.1:8100/health")
    judge_cli = resolve_judge_cli()
    judge_preflight = (
        check_judge_preflight_command(judge_cli, str(judge_status["resolved"]))
        if check_judge_preflight and judge_status["resolved"] and judge_cli["ok"]
        else {"checked": False}
    )
    reranker_package = check_python_module("FlagEmbedding")
    runner_support = check_rerank_runner_support(Path(__file__).resolve().parents[2])
    missing = [name for name, present in env_status.items() if not present]
    if not judge_status["resolved"]:
        missing.append("EVAL_JUDGE_MODEL")
    if not runner_support["ok"]:
        missing.append("cross_encoder_reranker_runner")
    if not reranker_package["ok"]:
        missing.append("FlagEmbedding")
    if not sidecar["ok"]:
        missing.append("sidecar:http://127.0.0.1:8100")
    elif sidecar.get("reranker_enabled") is not True:
        missing.append("sidecar_reranker_enabled")
    if not judge_cli["ok"]:
        missing.append("judge_cli")
    if judge_preflight.get("checked") and not judge_preflight.get("ok"):
        missing.append("judge_preflight")
    if not rerank_scores_path.exists():
        missing.append("scores_rerank.csv")
    return {
        "scores_rerank_path": str(rerank_scores_path),
        "scores_rerank_exists": rerank_scores_path.exists(),
        "env": env_status,
        "judge_model": judge_status,
        "sidecar": sidecar,
        "judge_cli": judge_cli,
        "judge_preflight": judge_preflight,
        "reranker_package": reranker_package,
        "runner_support": runner_support,
        "ready_to_run_rerank": not missing,
        "missing_prerequisites": missing,
        "rerun_command": None
        if not runner_support["ok"]
        else (
            "GRAPHRAG_RERANKER_ENABLED=true GRAPHRAG_LLM_API_KEY=<zhipu-key> "
            "python -m eval.run_eval --rerank-ablation --force-retrieval --force-judge"
        ),
    }


def resolve_original_judge_model(manifest_path: Path) -> str | None:
    try:
        payload = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    value = payload.get("run_config", {}).get("judge_model")
    return value if isinstance(value, str) and value else None


def check_sidecar_health(url: str) -> dict[str, Any]:
    try:
        with urllib.request.urlopen(url, timeout=5) as response:
            body = response.read(500).decode("utf-8", errors="replace")
        parsed = json.loads(body)
        return {
            "ok": True,
            "url": url,
            "status": response.status,
            "body": body,
            "reranker_enabled": parsed.get("reranker_enabled"),
            "reranker_model": parsed.get("reranker_model"),
        }
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        return {"ok": False, "url": url, "error": str(exc)}
    except json.JSONDecodeError as exc:
        return {"ok": False, "url": url, "error": str(exc)}


def check_python_module(module_name: str) -> dict[str, Any]:
    spec = importlib.util.find_spec(module_name)
    return {"ok": spec is not None, "module": module_name}


def check_command_version(command: list[str]) -> dict[str, Any]:
    executable = shutil.which(command[0])
    if not executable:
        return {"ok": False, "command": command[0], "error": "not found"}
    try:
        completed = subprocess.run(
            command,
            check=False,
            capture_output=True,
            text=True,
            timeout=10,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        return {"ok": False, "command": command[0], "path": executable, "error": str(exc)}
    output = (completed.stdout or completed.stderr).strip()
    return {
        "ok": completed.returncode == 0,
        "command": command[0],
        "path": executable,
        "returncode": completed.returncode,
        "version": output,
    }


def resolve_judge_cli() -> dict[str, Any]:
    agy = check_command_version(["agy", "--version"])
    if agy["ok"]:
        return {**agy, "backend": "agy"}
    gemini = check_command_version(["gemini", "--version"])
    return {**gemini, "backend": "gemini"}


def check_judge_preflight_command(judge_cli: dict[str, Any], model: str) -> dict[str, Any]:
    executable = judge_cli.get("path")
    backend = judge_cli.get("backend")
    if not executable or not backend:
        return {"checked": True, "ok": False, "command": backend or "unknown", "error": "not found"}
    if backend == "agy":
        command = [
            executable,
            "--prompt",
            "{prompt}",
            "--print-timeout",
            os.getenv("EVAL_JUDGE_TIMEOUT", "5m"),
            "--model",
            model,
        ]
    else:
        command = [executable, "--prompt", "", "--output-format", "text", "--model", model]
    try:
        stdin = '只输出 JSON：{"status":"ok"}。不要调用工具，不要输出其他内容。'
        if "{prompt}" in command:
            command[command.index("{prompt}")] = stdin
            stdin = None
        completed = subprocess.run(
            command,
            input=stdin,
            check=False,
            capture_output=True,
            text=True,
            timeout=60,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        return {
            "checked": True,
            "ok": False,
            "command": backend,
            "model": model,
            "error": str(exc),
        }
    output = (completed.stdout or completed.stderr).strip()
    return {
        "checked": True,
        "ok": completed.returncode == 0 and '"status"' in output and '"ok"' in output,
        "command": backend,
        "model": model,
        "returncode": completed.returncode,
        "output_excerpt": output[:600],
    }


def check_rerank_runner_support(graphrag_service_dir: Path) -> dict[str, Any]:
    checked_paths = [
        graphrag_service_dir / "eval" / "run_eval.py",
        graphrag_service_dir / "graphrag_service" / "lightrag_client.py",
        graphrag_service_dir / "graphrag_service" / "config.py",
    ]
    haystack = "\n".join(
        path.read_text(encoding="utf-8")
        for path in checked_paths
        if path.exists()
    )
    required_markers = ("reranker_enabled", "reranker_model", "reranker_config_hash")
    present = [marker for marker in required_markers if marker in haystack]
    return {
        "ok": len(present) == len(required_markers),
        "checked_paths": [str(path) for path in checked_paths],
        "required_markers": list(required_markers),
        "present_markers": present,
        "note": (
            "The current eval runner records a reranker switch in manifest fingerprints."
            if len(present) == len(required_markers)
            else "No cross-encoder reranker switch is wired into the current eval runner; running eval.run_eval now would be an ordinary re-evaluation, not the required single-variable rerank ablation."
        ),
    }


def write_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    if not rows:
        return
    fieldnames = list(rows[0].keys())
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def plot_routing(rows: list[dict[str, Any]], output: Path) -> None:
    plt = load_pyplot()
    overall = [row for row in rows if row["scope"] == "overall"]
    names = [row["strategy"] for row in overall]
    values = [row["mean_quality"] for row in overall]
    colors = ["#4477aa" if name != "oracle" else "#228833" for name in names]
    fig, axis = plt.subplots(figsize=(11, 5.8))
    axis.bar(range(len(names)), values, color=colors)
    naive = next(row["mean_quality"] for row in overall if row["strategy"] == "always-naive")
    axis.axhline(naive, color="#cc3311", linestyle="--", linewidth=1.4, label="always-naive")
    axis.set_xticks(range(len(names)), names, rotation=25, ha="right")
    axis.set_ylabel("Mean quality score")
    axis.set_ylim(0, 10)
    axis.set_title("Post-hoc routing policy upper bounds")
    axis.legend()
    fig.tight_layout()
    fig.savefig(output, dpi=180)
    plt.close(fig)


def plot_gating(rows: list[dict[str, Any]], output: Path) -> None:
    plt = load_pyplot()
    fig, axis = plt.subplots(figsize=(8, 5))
    for group in GRAPH_GROUPS:
        group_rows = [
            row for row in rows if row["scope"] == "D_only" and row["group"] == group
        ]
        group_rows.sort(key=lambda row: row["assumed_abstain_score"])
        axis.plot(
            [row["assumed_abstain_score"] for row in group_rows],
            [row["gated_quality"] for row in group_rows],
            marker="o",
            label=f"{group} gated",
        )
        axis.axhline(
            group_rows[0]["baseline_quality"],
            linestyle="--",
            linewidth=1,
            alpha=0.45,
            label=f"{group} baseline",
        )
    axis.set_xlabel("Assumed abstention score a")
    axis.set_ylabel("D-category mean quality")
    axis.set_ylim(0, 10)
    axis.set_title("Category-proxy abstention sensitivity")
    axis.legend(ncol=2, fontsize=8)
    fig.tight_layout()
    fig.savefig(output, dpi=180)
    plt.close(fig)


def plot_rerank(
    rerank_rows: list[dict[str, Any]],
    base_rows: list[ScoreRow],
    output: Path,
    *,
    available: bool,
) -> None:
    plt = load_pyplot()
    fig, axis = plt.subplots(figsize=(8, 5))
    naive_base = mean(row.quality for row in base_rows if row.group == "naive")
    if available:
        overall = [row for row in rerank_rows if row["scope"] == "overall" and row["group"] in GRAPH_GROUPS]
        groups = [row["group"] for row in overall]
        x = range(len(groups))
        axis.bar([value - 0.18 for value in x], [row["without_rerank_quality"] for row in overall], width=0.36, label="without rerank")
        axis.bar([value + 0.18 for value in x], [row["with_rerank_quality"] for row in overall], width=0.36, label="with rerank")
        axis.set_xticks(list(x), groups)
        axis.legend()
    else:
        groups = GRAPH_GROUPS
        values = [mean(row.quality for row in base_rows if row.group == group) for group in groups]
        axis.bar(groups, values, color="#bbbbbb", label="without rerank")
        axis.text(
            0.5,
            0.92,
            "scores_rerank.csv not available",
            transform=axis.transAxes,
            ha="center",
            va="center",
            fontsize=12,
        )
    axis.axhline(naive_base, color="#cc3311", linestyle="--", linewidth=1.4, label="naive baseline")
    axis.set_ylabel("Mean quality score")
    axis.set_ylim(0, 10)
    axis.set_title("Rerank ablation status")
    axis.legend()
    fig.tight_layout()
    fig.savefig(output, dpi=180)
    plt.close(fig)


def render_report(
    *,
    rows: list[ScoreRow],
    manifest: dict[str, Any],
    routing_rows: list[dict[str, Any]],
    gating_rows: list[dict[str, Any]],
    route_details: dict[str, Any],
    rerank_rows: list[dict[str, Any]],
    rerank_summary: dict[str, Any],
    rerank_preflight: dict[str, Any],
) -> str:
    overall = [row for row in routing_rows if row["scope"] == "overall"]
    d_gating = [row for row in gating_rows if row["scope"] == "D_graph_mean"]
    run_config = manifest.get("run_config", {})
    lines = [
        "# GraphRAG 改进验证结果",
        "",
        "## 数据核验",
        "",
        f"- 数据来源：`eval/results/scores.csv`，运行指纹 `{manifest.get('evaluation_run_id', 'unknown')}`。",
        f"- 查询与语料：queries_hash `{run_config.get('queries_hash', 'unknown')}`；corpus_hash `{run_config.get('corpus_hash', 'unknown')}`。",
        f"- 判分：{run_config.get('judge_backend', 'unknown')} / {run_config.get('judge_model', 'unknown')}；生成模型 `{run_config.get('llm_model', 'unknown')}`。",
        "- 行数与均值已通过脚本硬检查：64 行，16 条查询 × 4 模式；四模式均值与既有基准一致。",
        "",
        "## 任务一：自适应路由后处理",
        "",
        "| 策略 | 综合均分 | Δ vs naive | 均延迟(s) | 选择分布 |",
        "|---|---:|---:|---:|---|",
    ]
    for row in overall:
        lines.append(
            "| {strategy} | {mean_quality:.4f} | {delta_vs_naive:+.4f} | {mean_latency_s:.2f} | "
            "naive={selected_naive}, local={selected_local}, global={selected_global}, hybrid={selected_hybrid} |".format(
                **row
            )
        )
    oracle = next(row for row in overall if row["strategy"] == "oracle")
    loocv = next(row for row in overall if row["strategy"] == "category-rule-loocv")
    category_rule = route_details["category_rule"]
    lines.extend(
        [
            "",
            f"类别规则为 A->{category_rule['A']}、B->{category_rule['B']}、C->{category_rule['C']}、D->{category_rule['D']}，因此类别层规则退化为 always-naive。",
            f"逐查询 oracle 均分为 {oracle['mean_quality']:.4f}，较 naive 高 {oracle['delta_vs_naive']:+.4f}，但仅 {len(route_details['oracle_non_naive_wins'])}/16 条查询由非 naive 模式胜出。",
            f"LOOCV 类别规则均分为 {loocv['mean_quality']:.4f}，较 naive {loocv['delta_vs_naive']:+.4f}，说明当前 16 条数据不足以学习出优于 naive 的类别规则。",
            "",
            "非 naive oracle 胜出查询：",
            "",
            "| query_id | 类别 | 选择模式 | 选择质量 | naive质量 | Δ |",
            "|---|---|---|---:|---:|---:|",
        ]
    )
    for item in route_details["oracle_non_naive_wins"]:
        lines.append(
            "| {query_id} | {category} | {selected_group} | {selected_quality:.4f} | {naive_quality:.4f} | {delta_vs_naive:+.4f} |".format(
                **item
            )
        )
    lines.extend(
        [
            "",
            "## 任务二：相关度门控 / 拒答模拟",
            "",
            "当前产物缺少真实逐查询相关度分数；`cases[].relevance` 为系统合成排序分，不能作为阈值优化依据。因此本节采用分支 B：将 D 类视为语料外代理类别，对图检索答案替换为假设拒答分 `a` 做敏感性扫描。",
            "",
            "| 假设拒答分 a | D类图模式原均分 | D类代理门控均分 | Δ |",
            "|---:|---:|---:|---:|",
        ]
    )
    for row in d_gating:
        lines.append(
            f"| {row['assumed_abstain_score']} | {row['baseline_quality']:.4f} | {row['gated_quality']:.4f} | {row['delta']:+.4f} |"
        )
    best_threshold = min(
        row["assumed_abstain_score"] for row in d_gating if row["delta"] > 0
    )
    lines.extend(
        [
            "",
            f"在该代理设定下，当拒答答案可获得至少 {best_threshold} 分时，D 类图模式均值高于原图检索 D 类均值。该结论只说明“拒答可能缓解语料外幻觉”，不等价于真实相关度阈值已经被验证。",
            "",
            "## 任务三：rerank 消融",
            "",
        ]
    )
    if rerank_rows:
        lines.extend(
            [
                "| 模式 | 无rerank均分 | 有rerank均分 | Δ | 无rerank延迟(s) | 有rerank延迟(s) |",
                "|---|---:|---:|---:|---:|---:|",
            ]
        )
        for row in rerank_rows:
            if row["scope"] != "overall":
                continue
            lines.append(
                "| {group} | {without_rerank_quality:.4f} | {with_rerank_quality:.4f} | {delta:+.4f} | {without_rerank_latency_s:.2f} | {with_rerank_latency_s:.2f} |".format(
                    **row
                )
            )
        lines.append("")
        lines.append(
            "rerank 消融结果已按 `scores_rerank.csv` 生成；"
            f"无 rerank 基线使用 `{rerank_summary['baseline_path']}`，"
            "解读时仍须限定为 16 条小样本描述性对比。"
        )
        lines.append(
            "注意：该 rerank 图表使用 `agy / gemini-2.5-pro` 对无 rerank 与有 rerank "
            "两组结果进行同口径重判，与原始 `scores.csv` 的旧 judge 绝对分数不属于同一尺度；"
            "因此只应读取组内 Δ，不应与主实验图表的绝对均分直接对读。"
        )
        runtime_summary = rerank_summary.get("runtime_summary", {})
        if runtime_summary.get("available"):
            groups = runtime_summary["groups"]
            changed_counts = "、".join(
                f"{group} {groups[group]['case_order_changed']}/{groups[group]['total']}"
                for group in GRAPH_GROUPS
            )
            if runtime_summary["all_graph_answers_unchanged"]:
                lines.append(
                    "runtime 记录显示，rerank 后图检索候选集合或顺序发生变化"
                    f"（{changed_counts}），但三种图模式共 "
                    f"{runtime_summary['total_graph_answers']} 个最终 answer 文本全部未变。"
                    "结合 LightRAG `openai_complete_if_cache` 与 query cache hit 日志，"
                    "该 run 只能证明 reranker 接入和候选重排生效，不能验证 rerank 对生成答案质量的影响；"
                    "不应解释为 rerank 已确认无收益。"
                )
            else:
                lines.append(
                    "runtime 记录显示，rerank 后存在最终 answer 文本变化；可继续按同 judge 评分解释质量差异。"
                )
    else:
        lines.extend(
            [
                f"尚未找到 `{rerank_summary['path']}`。当前已生成占位图 `rerank_ablation.png`，用于标记该消融仍未完成；若论文需要报告 rerank 消融结果，必须补跑该文件，否则应如实保留为未完成局限。",
                "",
                "当前 rerank 前置检查结果如下：",
                "",
                f"- `scores_rerank.csv`：{'已存在' if rerank_preflight['scores_rerank_exists'] else '缺失'}。",
                f"- `GRAPHRAG_LLM_API_KEY`：{'已设置' if rerank_preflight['env']['GRAPHRAG_LLM_API_KEY'] else '未设置'}。",
                f"- judge model：{rerank_preflight['judge_model']['resolved'] or '未解析'}。",
                f"- sidecar `127.0.0.1:8100`：{'可访问' if rerank_preflight['sidecar']['ok'] else '不可访问'}。",
                f"- sidecar reranker：{'已启用' if rerank_preflight['sidecar'].get('reranker_enabled') else '未确认启用'}。",
                f"- `FlagEmbedding`：{'可导入' if rerank_preflight['reranker_package']['ok'] else '不可导入'}。",
                f"- cross-encoder rerank runner：{'已接入' if rerank_preflight['runner_support']['ok'] else '未接入'}。",
                f"- judge CLI `{rerank_preflight['judge_cli'].get('backend', 'unknown')}`：{'可用' if rerank_preflight['judge_cli']['ok'] else '不可用'}"
                + (
                    f"（{rerank_preflight['judge_cli'].get('version', '')}）"
                    if rerank_preflight["judge_cli"].get("version")
                    else "。"
                ),
                "- judge preflight："
                + render_judge_preflight_status(rerank_preflight["judge_preflight"]),
            ]
        )
    lines.extend(
        [
            "",
            "## 回填建议",
            "",
            "- §6 增补 oracle 上界：逐查询 oracle 为 8.9792，说明理论空间存在但只集中在少数查询。",
            "- §7.1 删除 “C 类路由至 hybrid” 的规则路由表述，改为类别规则与 LOOCV 均退化为 naive。",
            "- §7.2 写入分支 B 代理验证，明确缺少真实相关度阈值数据。",
        ]
    )
    if rerank_rows:
        lines.append("- §6.5/§9.2 写入 rerank 消融结果，并注明实际 reranker 模型、生成缓存阻断与小样本描述性限制。")
    else:
        lines.append("- §9.2 在 rerank 消融完成前，不应写成“已做 rerank 消融”。")
    lines.append("")
    return "\n".join(lines)


def render_judge_preflight_status(preflight: dict[str, Any]) -> str:
    if not preflight.get("checked"):
        return "未执行。"
    if preflight.get("ok"):
        return "通过。"
    excerpt = preflight.get("output_excerpt") or preflight.get("error") or "unknown error"
    lines = [line.strip() for line in str(excerpt).splitlines() if line.strip()]
    selected = next(
        (
            line
            for line in lines
            if "Error authenticating" in line or "IneligibleTierError" in line
        ),
        lines[0] if lines else "unknown error",
    )
    return "失败；" + selected[:180] + "。"


def load_pyplot():
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    return plt


if __name__ == "__main__":
    main()
