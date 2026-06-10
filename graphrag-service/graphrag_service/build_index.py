from __future__ import annotations

import argparse
from pathlib import Path

from .config import load_config, require_api_key
from .indexer import build_index


def main() -> None:
    parser = argparse.ArgumentParser(description="Build the GraphRAG historical case index.")
    parser.add_argument("--cases-dir", type=Path, default=Path("data/cases"))
    parser.add_argument("--index-dir", type=Path, default=Path("runtime/index"))
    parser.add_argument("--reset", action="store_true")
    args = parser.parse_args()

    config = load_config()
    require_api_key(config)
    stats = build_index(args.cases_dir, args.index_dir, reset=args.reset, config=config)
    print(
        "Built GraphRAG index: "
        f"cases={stats.cases_indexed} corpus_hash={stats.corpus_hash} built_at={stats.built_at}"
    )


if __name__ == "__main__":
    main()
