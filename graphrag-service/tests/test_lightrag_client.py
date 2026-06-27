from __future__ import annotations

import asyncio
from types import SimpleNamespace

from lightrag.kg import shared_storage

from graphrag_service.lightrag_client import LightRagClient


def test_initialize_storages_before_pipeline_status(monkeypatch) -> None:
    events: list[str] = []

    class FakeRag:
        async def initialize_storages(self) -> None:
            events.append("storages")

    async def fake_initialize_pipeline_status() -> None:
        events.append("pipeline")

    monkeypatch.setattr(
        shared_storage,
        "initialize_pipeline_status",
        fake_initialize_pipeline_status,
    )
    client = LightRagClient.__new__(LightRagClient)
    client._rag = FakeRag()
    client._initialized = False

    asyncio.run(client._initialize())
    asyncio.run(client._initialize())

    assert events == ["storages", "pipeline"]


def test_query_param_disables_rerank_by_default() -> None:
    class FakeQueryParam:
        def __init__(
            self,
            mode: str,
            only_need_context: bool = False,
            enable_rerank: bool = True,
        ):
            self.mode = mode
            self.only_need_context = only_need_context
            self.enable_rerank = enable_rerank

    client = LightRagClient.__new__(LightRagClient)
    client.config = SimpleNamespace(reranker_enabled=False)
    client._query_param_cls = FakeQueryParam

    param = client._create_query_param("local", True)

    assert param.mode == "local"
    assert param.only_need_context is True
    assert param.enable_rerank is False
