from __future__ import annotations

import argparse
import csv
import json
from collections import defaultdict
from pathlib import Path
from statistics import mean
from typing import Any

from .judge import GROUPS, METRICS
from .run_eval import EVAL_DIR, RESULTS_DIR, RUNTIME_DIR, load_completed


PLOTS_DIR = EVAL_DIR / "plots"


def generate_report(
    scores_path: Path = RESULTS_DIR / "scores.csv",
    manifest_path: Path = RESULTS_DIR / "run_manifest.json",
) -> None:
    rows = _load_scores(scores_path)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    run_id = manifest["evaluation_run_id"]
    progress = load_completed(RUNTIME_DIR / "runs" / run_id / "progress.jsonl")
    if not rows:
        raise ValueError(f"{scores_path}: no score rows")
    PLOTS_DIR.mkdir(parents=True, exist_ok=True)
    _plot_overall_scores(rows, PLOTS_DIR / "quality_scores.png")
    _plot_category_scores(rows, PLOTS_DIR / "quality_by_category.png")
    _plot_latency_quality(rows, PLOTS_DIR / "latency_quality.png")
    _write_case_study(progress, RESULTS_DIR / "case_study.md")


def _plot_overall_scores(rows: list[dict[str, Any]], output: Path) -> None:
    plt = _load_pyplot()
    width = 0.2
    x = list(range(len(GROUPS)))
    fig, axis = plt.subplots(figsize=(9, 5))
    for index, metric in enumerate(METRICS):
        values = [
            mean(float(row[metric]) for row in rows if row["group"] == group)
            for group in GROUPS
        ]
        positions = [value + (index - 1) * width for value in x]
        axis.bar(positions, values, width=width, label=metric.title())
    axis.set_xticks(x, GROUPS)
    axis.set_ylim(0, 10)
    axis.set_ylabel("Mean judge score")
    axis.set_title("GraphRAG retrieval quality by group")
    axis.legend()
    fig.tight_layout()
    fig.savefig(output, dpi=180)
    plt.close(fig)


def _plot_category_scores(rows: list[dict[str, Any]], output: Path) -> None:
    plt = _load_pyplot()
    categories = ("A", "B", "C", "D")
    fig, axes = plt.subplots(2, 2, figsize=(11, 8), sharey=True)
    for category, axis in zip(categories, axes.flat, strict=True):
        values = []
        for group in GROUPS:
            group_rows = [
                row for row in rows if row["category"] == category and row["group"] == group
            ]
            values.append(
                mean(
                    mean(float(row[metric]) for metric in METRICS)
                    for row in group_rows
                )
            )
        axis.bar(GROUPS, values)
        axis.set_title(f"Category {category}")
        axis.set_ylim(0, 10)
        axis.tick_params(axis="x", rotation=20)
    fig.supylabel("Mean of three quality metrics")
    fig.suptitle("Quality by query category")
    fig.tight_layout()
    fig.savefig(output, dpi=180)
    plt.close(fig)


def _plot_latency_quality(rows: list[dict[str, Any]], output: Path) -> None:
    plt = _load_pyplot()
    fig, axis = plt.subplots(figsize=(8, 5))
    for group in GROUPS:
        group_rows = [row for row in rows if row["group"] == group]
        latency = mean(float(row["latency_ms"]) for row in group_rows)
        quality = mean(
            mean(float(row[metric]) for metric in METRICS)
            for row in group_rows
        )
        axis.scatter(latency, quality, s=90)
        axis.annotate(group, (latency, quality), xytext=(5, 5), textcoords="offset points")
    axis.set_xlabel("Mean end-to-end latency (ms)")
    axis.set_ylabel("Mean quality score")
    axis.set_ylim(0, 10)
    axis.set_title("Latency-quality trade-off")
    fig.tight_layout()
    fig.savefig(output, dpi=180)
    plt.close(fig)


def _write_case_study(completed: dict[str, dict[str, Any]], output: Path) -> None:
    selected = []
    for category in ("B", "D"):
        matches = [
            record
            for record in completed.values()
            if record["query"]["category"] == category
        ]
        if not matches:
            raise ValueError(f"no completed category {category} query for case study")
        selected.append(sorted(matches, key=lambda item: item["query_id"])[0])

    lines = ["# Case Study Comparison", ""]
    fallback_counts: dict[str, int] = defaultdict(int)
    for record in completed.values():
        for group in ("local", "global", "hybrid"):
            source = record["retrievals"][group]["metrics"]["retrieval_source"]
            if source != "lightrag_context":
                fallback_counts[group] += 1
    lines.extend(
        [
            "## Retrieval Source Summary",
            "",
            "| Mode | Non-GraphRAG fallback count |",
            "|---|---:|",
            *[f"| {group} | {fallback_counts[group]} |" for group in ("local", "global", "hybrid")],
            "",
        ]
    )
    for record in selected:
        query = record["query"]
        lines.extend(
            [
                f"## {query['query_id']} ({query['category']})",
                "",
                query["query"],
                "",
                "| Group | Retrieval source | Retrieved cases | Answer | Judge rationale |",
                "|---|---|---|---|---|",
            ]
        )
        for group in GROUPS:
            retrieval = record["retrievals"][group]
            source = retrieval["metrics"]["retrieval_source"]
            case_ids = ", ".join(item["case_id"] for item in retrieval["cases"]) or "none"
            answer = _table_text(retrieval["answer"])
            rationale = _table_text(record["judge"]["scores"][group]["rationale"])
            lines.append(f"| {group} | {source} | {case_ids} | {answer} | {rationale} |")
        lines.append("")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    temporary.write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")
    temporary.replace(output)


def _load_scores(path: Path) -> list[dict[str, Any]]:
    with path.open(encoding="utf-8", newline="") as stream:
        return list(csv.DictReader(stream))


def _table_text(value: str) -> str:
    return " ".join(value.split()).replace("|", "\\|")


def _load_pyplot():
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    return plt


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate GraphRAG evaluation plots and case study.")
    parser.add_argument("--scores", type=Path, default=RESULTS_DIR / "scores.csv")
    parser.add_argument("--manifest", type=Path, default=RESULTS_DIR / "run_manifest.json")
    args = parser.parse_args()
    generate_report(args.scores, args.manifest)
    print(f"Generated evaluation report under {RESULTS_DIR} and {PLOTS_DIR}")


if __name__ == "__main__":
    main()
