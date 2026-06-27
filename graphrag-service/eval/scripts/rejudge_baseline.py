from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from eval.judge import judge_answers, preflight, resolve_backend, stable_hash
from eval.run_eval import (
    RESULTS_DIR,
    RUBRIC_PATH,
    RUNTIME_DIR,
    append_progress,
    completed_record,
    load_completed,
    write_scores,
    _write_json_atomic,
)


def main() -> None:
    parser = argparse.ArgumentParser(
        description=(
            "Reuse retrieval outputs from a published evaluation run and re-score "
            "them with a different judge backend without overwriting scores.csv."
        )
    )
    parser.add_argument("--original-run-id", required=True)
    parser.add_argument("--judge-backend", required=True)
    parser.add_argument("--judge-model", required=True)
    parser.add_argument("--scores-name", default="scores_no_rerank_agy.csv")
    parser.add_argument("--manifest-name", default="run_manifest_no_rerank_agy.json")
    parser.add_argument("--force-judge", action="store_true")
    args = parser.parse_args()

    backend = resolve_backend(args.judge_backend, args.judge_model)
    preflight(backend)

    original_manifest_path = RUNTIME_DIR / "runs" / args.original_run_id / "run_manifest.json"
    original_progress_path = RUNTIME_DIR / "runs" / args.original_run_id / "progress.jsonl"
    if not original_manifest_path.exists():
        raise RuntimeError(f"original run manifest not found: {original_manifest_path}")
    if not original_progress_path.exists():
        raise RuntimeError(f"original run progress not found: {original_progress_path}")

    original_manifest = json.loads(original_manifest_path.read_text(encoding="utf-8"))
    run_config = dict(original_manifest["run_config"])
    run_config.update(
        {
            "judge_backend": backend.name,
            "judge_model": backend.model,
            "judge_cli": backend.name,
            "judge_cli_version": backend.version,
            "same_retrievals_as_evaluation_run_id": args.original_run_id,
            "rerank_ablation": False,
        }
    )
    evaluation_run_id = stable_hash(run_config)
    run_dir = RUNTIME_DIR / "runs" / evaluation_run_id
    run_dir.mkdir(parents=True, exist_ok=True)
    _write_json_atomic(
        run_dir / "run_manifest.json",
        {"evaluation_run_id": evaluation_run_id, "run_config": run_config},
    )

    completed = load_completed(run_dir / "progress.jsonl")
    original_completed = load_completed(original_progress_path)
    rubric = RUBRIC_PATH.read_text(encoding="utf-8")
    expected_groups = ("naive", "local", "global", "hybrid")

    for query_id in sorted(original_completed):
        if query_id in completed and not args.force_judge:
            continue
        record = original_completed[query_id]
        retrievals = record["retrievals"]
        missing = [group for group in expected_groups if group not in retrievals]
        if missing:
            raise RuntimeError(f"{query_id} is missing retrieval groups: {missing}")
        judged = judge_answers(
            query=record["query"],
            answers={group: retrievals[group]["answer"] for group in expected_groups},
            rubric=rubric,
            backend=backend,
            cache_dir=RUNTIME_DIR / "cache" / "judge",
            force=args.force_judge,
        )
        new_record = completed_record(
            evaluation_run_id=evaluation_run_id,
            query=record["query"],
            retrievals=retrievals,
            judged=judged,
            backend=backend,
            corpus_hash=run_config["corpus_hash"],
        )
        append_progress(run_dir / "progress.jsonl", new_record)
        completed[query_id] = new_record
        print(f"judged {query_id}", flush=True)

    write_scores(RESULTS_DIR / args.scores_name, completed.values())
    _write_json_atomic(
        RESULTS_DIR / args.manifest_name,
        {"evaluation_run_id": evaluation_run_id, "run_config": run_config},
    )
    print(f"evaluation_run_id {evaluation_run_id}")


if __name__ == "__main__":
    main()
