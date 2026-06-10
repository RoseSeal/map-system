from __future__ import annotations

import asyncio
import hashlib
import json
import shutil
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .config import GraphRagConfig, load_config
from .corpus import CaseRecord, load_cases
from .embeddings import embed_texts_sync
from .lightrag_client import LightRagClient


@dataclass(frozen=True)
class CatalogStats:
    cases_indexed: int
    built_at: str
    corpus_hash: str
    case_ids: list[str]


def build_index(
    cases_dir: Path,
    working_dir: Path,
    reset: bool = False,
    config: GraphRagConfig | None = None,
) -> CatalogStats:
    config = config or load_config()
    if reset and working_dir.exists():
        shutil.rmtree(working_dir)
    working_dir.mkdir(parents=True, exist_ok=True)

    cases = load_cases(cases_dir)
    texts = [case.full_text for case in cases]
    embeddings = embed_texts_sync(texts, config)
    _write_catalog(working_dir, cases, embeddings)
    asyncio.run(_insert_lightrag_documents(working_dir, cases, config))
    return _write_manifest(working_dir, cases, config)


async def _insert_lightrag_documents(working_dir: Path, cases: list[CaseRecord], config: GraphRagConfig) -> None:
    client = LightRagClient(working_dir, config)
    for case in cases:
        await client.insert(case.full_text)


def _write_catalog(working_dir: Path, cases: list[CaseRecord], embeddings: list[list[float]]) -> None:
    if len(cases) != len(embeddings):
        raise ValueError("embedding count does not match case count")
    catalog = {
        case.case_id: case.to_catalog_entry(embedding)
        for case, embedding in zip(cases, embeddings, strict=True)
    }
    _write_json(working_dir / "catalog.json", catalog)


def _write_manifest(working_dir: Path, cases: list[CaseRecord], config: GraphRagConfig) -> CatalogStats:
    built_at = datetime.now(timezone.utc).isoformat()
    corpus_hash = _corpus_hash(cases)
    stats = CatalogStats(
        cases_indexed=len(cases),
        built_at=built_at,
        corpus_hash=corpus_hash,
        case_ids=[case.case_id for case in cases],
    )
    _write_json(
        working_dir / "manifest.json",
        {
            "cases_indexed": stats.cases_indexed,
            "built_at": stats.built_at,
            "corpus_hash": stats.corpus_hash,
            "case_ids": stats.case_ids,
            "embed_model": config.embed_model,
            "embedding_dim": config.embedding_dim,
        },
    )
    return stats


def _corpus_hash(cases: list[CaseRecord]) -> str:
    digest = hashlib.sha256()
    for case in cases:
        digest.update(case.case_id.encode("utf-8"))
        digest.update(b"\0")
        digest.update(case.full_text.encode("utf-8"))
        digest.update(b"\0")
    return digest.hexdigest()


def _write_json(path: Path, payload: dict[str, Any]) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
