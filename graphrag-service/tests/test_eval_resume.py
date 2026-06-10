from __future__ import annotations

import json
from pathlib import Path

from eval.judge import GROUPS, JudgeBackend, judge_answers, stable_hash
from eval.run_eval import append_progress, load_completed


def test_progress_is_isolated_by_evaluation_run_id(tmp_path: Path) -> None:
    first = tmp_path / stable_hash({"backend": "gemini"}) / "progress.jsonl"
    second = tmp_path / stable_hash({"backend": "claude"}) / "progress.jsonl"
    append_progress(first, _completed("A-01"))

    assert set(load_completed(first)) == {"A-01"}
    assert load_completed(second) == {}


def test_failed_record_is_retried_on_resume(tmp_path: Path) -> None:
    path = tmp_path / "progress.jsonl"
    append_progress(path, _completed("A-01"))
    append_progress(path, {"query_id": "A-01", "status": "retrieval_failed"})

    assert load_completed(path) == {}


def test_force_judge_bypasses_cached_result(tmp_path: Path) -> None:
    backend = JudgeBackend("gemini", "/bin/gemini", "model-a", "1.0")
    query = {
        "query_id": "A-01",
        "category": "A",
        "situation": {},
        "query": "query",
    }
    answers = {group: group for group in GROUPS}
    calls = {"count": 0}

    def runner(backend, prompt):
        calls["count"] += 1
        return json.dumps(
            {
                "answers": {
                    f"Answer-{index}": {
                        "comprehensiveness": 7,
                        "diversity": 7,
                        "empowerment": 7,
                        "rationale": "ok",
                    }
                    for index in range(1, 5)
                }
            }
        )

    first = judge_answers(query, answers, "rubric", backend, tmp_path, runner=runner)
    cached = judge_answers(query, answers, "rubric", backend, tmp_path, runner=runner)
    judge_answers(query, answers, "rubric", backend, tmp_path, force=True, runner=runner)

    assert calls["count"] == 2
    assert cached["judged_at"] == first["judged_at"]


def test_run_fingerprint_changes_with_rubric_backend_or_model() -> None:
    base = {"rubric": "a", "backend": "gemini", "model": "model-a"}

    assert stable_hash(base) != stable_hash({**base, "rubric": "b"})
    assert stable_hash(base) != stable_hash({**base, "backend": "claude"})
    assert stable_hash(base) != stable_hash({**base, "model": "model-b"})


def _completed(query_id: str) -> dict:
    return {"query_id": query_id, "status": "completed", "rows": []}
