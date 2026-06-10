from __future__ import annotations

import httpx

from .config import GraphRagConfig, require_api_key


async def embed_texts(texts: list[str], config: GraphRagConfig) -> list[list[float]]:
    require_api_key(config)
    url = config.llm_base_url.rstrip("/") + "/embeddings"
    async with httpx.AsyncClient(timeout=60) as client:
        response = await client.post(
            url,
            headers={"Authorization": f"Bearer {config.llm_api_key}"},
            json={"model": config.embed_model, "input": texts},
        )
        response.raise_for_status()
    return _parse_embedding_response(response.json(), expected=len(texts))


def embed_texts_sync(texts: list[str], config: GraphRagConfig) -> list[list[float]]:
    require_api_key(config)
    url = config.llm_base_url.rstrip("/") + "/embeddings"
    with httpx.Client(timeout=60) as client:
        response = client.post(
            url,
            headers={"Authorization": f"Bearer {config.llm_api_key}"},
            json={"model": config.embed_model, "input": texts},
        )
        response.raise_for_status()
    return _parse_embedding_response(response.json(), expected=len(texts))


def _parse_embedding_response(payload: dict, expected: int) -> list[list[float]]:
    data = payload.get("data", [])
    if len(data) != expected:
        raise ValueError(f"embedding response returned {len(data)} vectors, expected {expected}")
    data = sorted(data, key=lambda item: item.get("index", 0))
    return [[float(value) for value in item["embedding"]] for item in data]
