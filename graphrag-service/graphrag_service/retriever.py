from __future__ import annotations

import json
import math
import re
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Protocol

from .config import GraphRagConfig, load_config
from .embeddings import embed_texts
from .lightrag_client import LightRagClient, LightRagUnavailable


VALID_MODES = {"local", "global", "hybrid"}


class AsyncLightRagClient(Protocol):
    async def query(self, query: str, mode: str, only_need_context: bool = False) -> str:
        ...


@dataclass(frozen=True)
class RetrieveRequest:
    query: str | None
    situation: dict[str, Any] | None
    mode: str | None
    top_k: int | None


class RetrieveError(ValueError):
    def __init__(self, status_code: int, error: str):
        super().__init__(error)
        self.status_code = status_code
        self.error = error


async def retrieve(
    req: RetrieveRequest,
    config: GraphRagConfig | None = None,
    client_factory: Callable[[Path, GraphRagConfig], AsyncLightRagClient] | None = None,
) -> dict[str, Any]:
    config = config or load_config()
    working_dir = config.working_dir
    manifest = _load_manifest(working_dir)
    catalog = _load_catalog(working_dir)

    top_k = req.top_k if req.top_k is not None else config.default_top_k
    if top_k < 1 or top_k > 10:
        raise RetrieveError(400, "top_k_out_of_range")

    mode = req.mode or config.default_mode
    if mode not in VALID_MODES:
        raise RetrieveError(400, "mode_out_of_range")

    if not (req.query and req.query.strip()) and not req.situation:
        raise RetrieveError(400, "empty_query")

    query_effective = build_effective_query(req.query, req.situation)
    start = time.perf_counter()
    answer = ""

    try:
        client = client_factory(working_dir, config) if client_factory else LightRagClient(working_dir, config)
        context = await client.query(query_effective, mode=mode, only_need_context=True)
        case_ids = _extract_case_ids(context, catalog)
        candidates = [catalog[case_id] for case_id in case_ids]
        if not candidates:
            candidates = await _embedding_candidates(query_effective, catalog, top_k, config)
        answer = await client.query(query_effective, mode=mode, only_need_context=False)
    except LightRagUnavailable:
        candidates = await _embedding_candidates(query_effective, catalog, top_k, config)
        answer = "未配置可用的 LightRAG 运行时；已基于 catalog embedding 返回相似案例。"

    ranked = _rerank(candidates, req.situation or {})
    cases = [_case_response(item) for item in ranked[:top_k]]
    if not cases and not answer:
        answer = "未找到相似案例。"

    return {
        "mode": mode,
        "query_effective": query_effective,
        "cases": cases,
        "answer": answer,
        "metrics": {
            "latency_ms": int((time.perf_counter() - start) * 1000),
            "tokens": {"prompt": 0, "completion": 0},
            "cases_indexed": manifest.get("cases_indexed", 0),
        },
    }


def build_effective_query(query: str | None, situation: dict[str, Any] | None) -> str:
    parts: list[str] = []
    if query and query.strip():
        parts.append(query.strip())
    if situation:
        labels = {
            "encounter_type": "会遇类型",
            "water_area": "水域",
            "risk_level": "风险等级",
            "own_ship_role": "本船责任",
            "visibility": "能见度",
            "target_count": "目标数量",
        }
        situation_text = "；".join(
            f"{label}: {situation[key]}"
            for key, label in labels.items()
            if key in situation and situation[key] not in (None, "")
        )
        if situation_text:
            parts.append("态势特征：" + situation_text)
    return "\n".join(parts).strip()


def _load_manifest(working_dir: Path) -> dict[str, Any]:
    path = working_dir / "manifest.json"
    if not path.exists():
        raise RetrieveError(503, "index_missing")
    return json.loads(path.read_text(encoding="utf-8"))


def _load_catalog(working_dir: Path) -> dict[str, dict[str, Any]]:
    path = working_dir / "catalog.json"
    if not path.exists():
        raise RetrieveError(503, "index_missing")
    return json.loads(path.read_text(encoding="utf-8"))


def _extract_case_ids(context: str, catalog: dict[str, dict[str, Any]]) -> list[str]:
    seen: set[str] = set()
    ids: list[str] = []
    for match in re.finditer(r"\bH-\d{2,}\b", context):
        case_id = match.group(0)
        if case_id in catalog and case_id not in seen:
            ids.append(case_id)
            seen.add(case_id)
    return ids


async def _embedding_candidates(
    query_effective: str,
    catalog: dict[str, dict[str, Any]],
    top_k: int,
    config: GraphRagConfig,
) -> list[dict[str, Any]]:
    query_embedding = (await embed_texts([query_effective], config))[0]
    scored: list[dict[str, Any]] = []
    for item in catalog.values():
        score = _cosine(query_embedding, item.get("embedding") or [])
        copied = dict(item)
        copied["_base_relevance"] = score
        scored.append(copied)
    scored.sort(key=lambda item: item.get("_base_relevance", 0.0), reverse=True)
    return scored[: max(top_k, 10)]


def _rerank(candidates: list[dict[str, Any]], situation: dict[str, Any]) -> list[dict[str, Any]]:
    ranked: list[dict[str, Any]] = []
    for rank, item in enumerate(candidates):
        copied = dict(item)
        score = float(copied.get("_base_relevance", 1.0 - rank * 0.05))
        for field in ("own_ship_type", "target_ship_type", "visibility"):
            if situation.get(field) and situation[field] == copied.get(field):
                score += 0.05
        for field in ("encounter_type", "water_area", "risk_level", "own_ship_role"):
            if situation.get(field) and situation[field] == copied.get(field):
                score += 0.08
        copied["relevance"] = round(max(0.0, min(1.0, score)), 4)
        ranked.append(copied)
    ranked.sort(key=lambda item: item["relevance"], reverse=True)
    return ranked


def _case_response(item: dict[str, Any]) -> dict[str, Any]:
    return {
        "case_id": item["case_id"],
        "title": item["title"],
        "relevance": item.get("relevance", item.get("_base_relevance", 0.0)),
        "water_area": item.get("water_area", ""),
        "visibility": item.get("visibility", ""),
        "own_ship_role": item.get("own_ship_role", ""),
        "encounter_type": item.get("encounter_type", ""),
        "risk_level": item.get("risk_level", ""),
        "target_summary": item.get("target_summary", ""),
        "colregs_rules": item.get("colregs_rules", []),
        "outcome": item.get("outcome", ""),
        "action_digest": item.get("action_digest", ""),
        "lesson": item.get("lesson", ""),
    }


def _cosine(left: list[float], right: list[float]) -> float:
    if not left or not right:
        return 0.0
    size = min(len(left), len(right))
    dot = sum(left[index] * right[index] for index in range(size))
    left_norm = math.sqrt(sum(value * value for value in left[:size]))
    right_norm = math.sqrt(sum(value * value for value in right[:size]))
    if left_norm == 0 or right_norm == 0:
        return 0.0
    return dot / (left_norm * right_norm)
