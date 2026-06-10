from __future__ import annotations

import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from graphrag_service.config import GraphRagConfig
from graphrag_service import retriever
from graphrag_service.server import app


class FakeLightRagClient:
    def __init__(self, *_):
        pass

    async def query(self, query: str, mode: str, only_need_context: bool = False) -> str:
        if only_need_context:
            return "case_id: H-02\ncase_id: H-03"
        return f"answer for {mode}: {query}"


def test_retrieve_returns_contract_shape(tmp_path: Path, monkeypatch) -> None:
    config = _write_index(tmp_path)
    monkeypatch.setattr("graphrag_service.retriever.load_config", lambda: config)
    monkeypatch.setattr("graphrag_service.retriever.LightRagClient", FakeLightRagClient)

    client = TestClient(app)
    response = client.post(
        "/retrieve",
        json={
            "situation": {
                "own_ship_role": "GIVE_WAY",
                "encounter_type": "CROSSING",
                "water_area": "限制水域",
                "visibility": "RESTRICTED_VISIBILITY",
                "risk_level": "WARNING",
            },
            "mode": "local",
            "top_k": 1,
        },
    )

    assert response.status_code == 200
    payload = response.json()
    assert payload["mode"] == "local"
    assert "query_effective" in payload
    assert len(payload["cases"]) == 1
    assert payload["cases"][0]["case_id"] == "H-02"
    assert payload["cases"][0]["risk_level"] == "WARNING"
    assert "answer" in payload
    assert "latency_ms" in payload["metrics"]


def test_retrieve_accepts_all_modes(tmp_path: Path, monkeypatch) -> None:
    config = _write_index(tmp_path)
    monkeypatch.setattr("graphrag_service.retriever.load_config", lambda: config)
    monkeypatch.setattr("graphrag_service.retriever.LightRagClient", FakeLightRagClient)
    client = TestClient(app)

    for mode in ("local", "global", "hybrid"):
        response = client.post("/retrieve", json={"query": "交叉相遇", "mode": mode, "top_k": 2})
        assert response.status_code == 200
        assert response.json()["mode"] == mode


def test_retrieve_rejects_empty_query(tmp_path: Path, monkeypatch) -> None:
    config = _write_index(tmp_path)
    monkeypatch.setattr("graphrag_service.retriever.load_config", lambda: config)
    client = TestClient(app)

    response = client.post("/retrieve", json={"mode": "local"})

    assert response.status_code == 400
    assert response.json() == {"error": "empty_query"}


def test_retrieve_rejects_top_k_out_of_range(tmp_path: Path, monkeypatch) -> None:
    config = _write_index(tmp_path)
    monkeypatch.setattr("graphrag_service.retriever.load_config", lambda: config)
    client = TestClient(app)

    response = client.post("/retrieve", json={"query": "交叉相遇", "top_k": 11})

    assert response.status_code == 400
    assert response.json() == {"error": "top_k_out_of_range"}


def test_retrieve_rejects_invalid_mode(tmp_path: Path, monkeypatch) -> None:
    config = _write_index(tmp_path)
    monkeypatch.setattr("graphrag_service.retriever.load_config", lambda: config)
    client = TestClient(app)

    response = client.post("/retrieve", json={"query": "交叉相遇", "mode": "invalid"})

    assert response.status_code == 400
    assert response.json() == {"error": "mode_out_of_range"}


def test_startup_fails_fast_without_api_key(tmp_path: Path, monkeypatch) -> None:
    config = _config(tmp_path / "index")
    monkeypatch.setattr("graphrag_service.server.load_config", lambda: config)

    with pytest.raises(RuntimeError, match="GRAPHRAG_LLM_API_KEY"):
        with TestClient(app):
            pass


def test_health_and_retrieve_report_missing_index(tmp_path: Path, monkeypatch) -> None:
    config = _config(tmp_path / "missing-index")
    monkeypatch.setattr("graphrag_service.server.load_config", lambda: config)
    monkeypatch.setattr("graphrag_service.retriever.load_config", lambda: config)
    client = TestClient(app)

    health = client.get("/health")
    retrieve_response = client.post("/retrieve", json={"query": "交叉相遇"})

    assert health.status_code == 503
    assert health.json() == {"status": "index_missing"}
    assert retrieve_response.status_code == 503
    assert retrieve_response.json() == {"error": "index_missing"}


def _write_index(tmp_path: Path) -> GraphRagConfig:
    index_dir = tmp_path / "index"
    index_dir.mkdir()
    (index_dir / "manifest.json").write_text(
        json.dumps(
            {
                "cases_indexed": 2,
                "built_at": "2026-06-04T00:00:00+00:00",
                "corpus_hash": "test",
                "case_ids": ["H-02", "H-03"],
            }
        ),
        encoding="utf-8",
    )
    (index_dir / "catalog.json").write_text(
        json.dumps(
            {
                "H-02": _catalog_case("H-02", "限制水域交叉相遇让路船减速右转", 1.0),
                "H-03": _catalog_case("H-03", "受限能见度交叉相遇保守减速", 0.8),
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    return _config(index_dir)


def _config(index_dir: Path) -> GraphRagConfig:
    return GraphRagConfig(
        llm_base_url="https://example.invalid/v1",
        llm_api_key="",
        llm_model="glm-4-flash",
        embed_model="embedding-3",
        working_dir=index_dir,
        default_top_k=5,
        default_mode="local",
        embedding_dim=4,
    )


def _catalog_case(case_id: str, title: str, first_embedding_value: float) -> dict:
    return {
        "case_id": case_id,
        "title": title,
        "synthetic": True,
        "water_area": "限制水域",
        "visibility": "RESTRICTED_VISIBILITY",
        "own_ship_role": "GIVE_WAY",
        "encounter_type": "CROSSING",
        "risk_level": "WARNING",
        "own_ship_type": "CARGO",
        "target_ship_type": "TANKER",
        "target_summary": "1 艘货船交叉接近",
        "kinematics": "CPA 0.3 nm / TCPA 6 min",
        "colregs_rules": ["Rule 15", "Rule 16"],
        "outcome": "SAFE",
        "body": "synthetic body",
        "action_digest": "减速并向右转向",
        "lesson": "及早避让",
        "source_file": f"{case_id}.md",
        "full_text": title,
        "embedding": [first_embedding_value, 0.0, 0.0, 0.0],
    }
