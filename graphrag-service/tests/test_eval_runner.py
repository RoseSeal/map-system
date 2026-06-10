from __future__ import annotations

import asyncio
from pathlib import Path

import httpx

from eval import run_eval
from eval.judge import GROUPS, JudgeBackend
from graphrag_service.config import GraphRagConfig


def test_retrieve_groups_runs_independent_groups_concurrently(
    tmp_path: Path,
    monkeypatch,
) -> None:
    active = 0
    max_active = 0

    async def result_for(group: str) -> dict:
        nonlocal active, max_active
        active += 1
        max_active = max(max_active, active)
        await asyncio.sleep(0.01)
        active -= 1
        return _retrieval_result(group)

    async def fake_naive(**kwargs):
        return await result_for("naive")

    class FakeClient:
        async def post(self, url, json):
            return FakeResponse(await result_for(json["mode"]))

    monkeypatch.setattr(run_eval, "RUNTIME_DIR", tmp_path / "runtime")
    monkeypatch.setattr(run_eval, "run_naive", fake_naive)

    results = asyncio.run(
        run_eval.retrieve_groups(
            query=_query(),
            config=_config(tmp_path / "index"),
            manifest=_manifest(),
            sidecar_url="http://sidecar.invalid",
            top_k=5,
            force=False,
            client=FakeClient(),
        )
    )

    assert set(results) == set(GROUPS)
    assert max_active == 4


def test_retrieval_http_failure_returns_retryable_progress_record(
    tmp_path: Path,
    monkeypatch,
) -> None:
    async def fake_naive(**kwargs):
        return _retrieval_result("naive")

    class FailingClient:
        async def post(self, url, json):
            request = httpx.Request("POST", url)
            return FailingResponse(request, json["mode"])

    monkeypatch.setattr(run_eval, "RUNTIME_DIR", tmp_path / "runtime")
    monkeypatch.setattr(run_eval, "run_naive", fake_naive)

    record = asyncio.run(
        run_eval.evaluate_query(
            evaluation_run_id="run-id",
            query=_query(),
            config=_config(tmp_path / "index"),
            manifest=_manifest(),
            rubric="rubric",
            backend=JudgeBackend("gemini", "/bin/gemini", "model-a", "1.0"),
            sidecar_url="http://sidecar.invalid",
            top_k=5,
            force_retrieval=False,
            force_judge=False,
            client=FailingClient(),
        )
    )

    assert record["query_id"] == "A-01"
    assert record["status"] == "retrieval_failed"
    assert "retrieval failed" in record["error"]

    progress_path = tmp_path / "progress.jsonl"
    run_eval.append_progress(
        progress_path,
        {"query_id": "A-01", "status": "completed", "rows": []},
    )
    run_eval.append_progress(progress_path, record)
    assert run_eval.load_completed(progress_path) == {}


class FakeResponse:
    def __init__(self, payload: dict):
        self.payload = payload

    def raise_for_status(self) -> None:
        pass

    def json(self) -> dict:
        return self.payload


class FailingResponse:
    def __init__(self, request: httpx.Request, group: str):
        self.request = request
        self.group = group

    def raise_for_status(self) -> None:
        response = httpx.Response(503, request=self.request)
        raise httpx.HTTPStatusError(
            f"{self.group} unavailable",
            request=self.request,
            response=response,
        )


def _query() -> dict:
    return {
        "query_id": "A-01",
        "category": "A",
        "query": "query",
        "situation": {"encounter_type": "HEAD_ON"},
    }


def _manifest() -> dict:
    return {
        "corpus_hash": "corpus",
        "built_at": "2026-06-04T00:00:00+00:00",
    }


def _config(index_dir: Path) -> GraphRagConfig:
    return GraphRagConfig(
        llm_base_url="https://example.invalid/v1",
        llm_api_key="test",
        llm_model="glm-4-flash",
        embed_model="embedding-3",
        working_dir=index_dir,
        default_top_k=5,
        default_mode="local",
        embedding_dim=4,
    )


def _retrieval_result(group: str) -> dict:
    return {
        "mode": group,
        "query_effective": "query",
        "cases": [],
        "answer": f"{group} answer",
        "metrics": {
            "latency_ms": 10,
            "retrieval_source": (
                "naive_embedding" if group == "naive" else "lightrag_context"
            ),
        },
    }
