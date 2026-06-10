from __future__ import annotations

import asyncio
import json
from pathlib import Path

from eval.baselines import run_naive
from graphrag_service.config import GraphRagConfig


def test_naive_baseline_truncates_after_rerank_before_generation(tmp_path: Path) -> None:
    index_dir = tmp_path / "index"
    index_dir.mkdir()
    catalog = {
        f"H-{index:02d}": _case(f"H-{index:02d}", relevance)
        for index, relevance in enumerate((0.2, 0.9, 0.7, 0.8), start=1)
    }
    (index_dir / "catalog.json").write_text(
        json.dumps(catalog, ensure_ascii=False),
        encoding="utf-8",
    )
    captured: dict[str, str] = {}

    async def candidates(query_effective, loaded_catalog, top_k, config):
        assert loaded_catalog == catalog
        return [
            dict(item, _base_relevance=item["test_relevance"])
            for item in loaded_catalog.values()
        ]

    async def generate(prompt, config):
        captured["prompt"] = prompt
        return "generated answer"

    result = asyncio.run(
        run_naive(
            query="交叉态势",
            situation={},
            top_k=2,
            config=_config(index_dir),
            candidate_loader=candidates,
            answer_generator=generate,
        )
    )

    assert [case["case_id"] for case in result["cases"]] == ["H-02", "H-04"]
    assert "H-02" in captured["prompt"]
    assert "H-04" in captured["prompt"]
    assert "H-01" not in captured["prompt"]
    assert "H-03" not in captured["prompt"]


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


def _case(case_id: str, relevance: float) -> dict:
    return {
        "case_id": case_id,
        "title": f"Case {case_id}",
        "full_text": f"Full text for {case_id}",
        "test_relevance": relevance,
        "water_area": "开阔水域",
        "visibility": "OPEN_VISIBILITY",
        "own_ship_role": "GIVE_WAY",
        "encounter_type": "CROSSING",
        "risk_level": "WARNING",
        "target_summary": "target",
        "colregs_rules": ["Rule 15"],
        "outcome": "SAFE",
        "action_digest": "act",
        "lesson": "lesson",
        "embedding": [relevance, 0.0, 0.0, 0.0],
    }
