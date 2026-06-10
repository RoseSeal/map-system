from __future__ import annotations

import time
from pathlib import Path
from typing import Any, Awaitable, Callable

import httpx

from graphrag_service.config import GraphRagConfig, require_api_key
# Step 4 intentionally reuses the production fallback ranking path so the
# naive baseline differs by retrieval strategy, not by duplicate scoring logic.
# Keep these imports aligned with retriever.py when that internal path changes.
from graphrag_service.retriever import (
    _case_response,
    _embedding_candidates,
    _load_catalog,
    _rerank,
    build_effective_query,
)


NAIVE_SYSTEM_PROMPT = (
    "你是海事避碰历史案例助手。仅依据给定案例上下文回答；"
    "说明可借鉴的案例事实、行动顺序与适用限制。若案例匹配较弱，必须明确说明。"
)
NAIVE_USER_PROMPT_TEMPLATE = (
    "## 查询\n{query_effective}\n\n"
    "## 历史案例上下文\n{context}\n\n"
    "## 回答要求\n用中文给出处置参考，并区分直接匹配事实与有限类比。"
)


async def run_naive(
    query: str,
    situation: dict[str, Any],
    top_k: int,
    config: GraphRagConfig,
    candidate_loader: Callable[
        [str, dict[str, dict[str, Any]], int, GraphRagConfig],
        Awaitable[list[dict[str, Any]]],
    ] = _embedding_candidates,
    answer_generator: Callable[[str, GraphRagConfig], Awaitable[str]] | None = None,
) -> dict[str, Any]:
    start = time.perf_counter()
    query_effective = build_effective_query(query, situation)
    catalog = _load_catalog(config.working_dir)
    candidates = await candidate_loader(query_effective, catalog, top_k, config)
    ranked = _rerank(candidates, situation)[:top_k]
    prompt = build_generation_prompt(query_effective, ranked)
    generator = answer_generator or generate_answer
    answer = await generator(prompt, config)
    return {
        "mode": "naive",
        "query_effective": query_effective,
        "cases": [_case_response(item) for item in ranked],
        "answer": answer,
        "metrics": {
            "latency_ms": int((time.perf_counter() - start) * 1000),
            "tokens": {"prompt": 0, "completion": 0},
            "cases_indexed": len(catalog),
            "retrieval_source": "naive_embedding",
        },
        "_estimated_input_text": f"{NAIVE_SYSTEM_PROMPT}\n{prompt}",
    }


def build_generation_prompt(query_effective: str, cases: list[dict[str, Any]]) -> str:
    context = "\n\n".join(
        f"### {item['case_id']} {item['title']}\n{item.get('full_text', '')}"
        for item in cases
    )
    return NAIVE_USER_PROMPT_TEMPLATE.format(
        query_effective=query_effective,
        context=context or "无可用案例",
    )


async def generate_answer(prompt: str, config: GraphRagConfig) -> str:
    require_api_key(config)
    url = config.llm_base_url.rstrip("/") + "/chat/completions"
    async with httpx.AsyncClient(timeout=120) as client:
        response = await client.post(
            url,
            headers={"Authorization": f"Bearer {config.llm_api_key}"},
            json={
                "model": config.llm_model,
                "messages": [
                    {"role": "system", "content": NAIVE_SYSTEM_PROMPT},
                    {"role": "user", "content": prompt},
                ],
                "temperature": 0.1,
            },
        )
        response.raise_for_status()
    payload = response.json()
    return str(payload["choices"][0]["message"]["content"]).strip()


def load_index_manifest(working_dir: Path) -> dict[str, Any]:
    path = working_dir / "manifest.json"
    if not path.exists():
        raise FileNotFoundError(f"{path}: build the GraphRAG index before running evaluation")
    import json

    return json.loads(path.read_text(encoding="utf-8"))
