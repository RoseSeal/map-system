from __future__ import annotations

import asyncio

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
