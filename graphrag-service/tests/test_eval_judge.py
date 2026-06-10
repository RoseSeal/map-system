from __future__ import annotations

import json

import pytest

from eval.judge import (
    GROUPS,
    JudgeBackend,
    JudgeError,
    build_judge_prompt,
    judge_fingerprint,
    parse_judge_output,
    resolve_backend,
)


def test_anonymous_answer_order_is_deterministic() -> None:
    query = _query()
    answers = {group: f"content {index}" for index, group in enumerate(GROUPS)}

    first_prompt, first_mapping = build_judge_prompt("rubric", query, answers)
    second_prompt, second_mapping = build_judge_prompt("rubric", query, answers)

    assert first_prompt == second_prompt
    assert first_mapping == second_mapping
    assert set(first_mapping.values()) == set(GROUPS)
    assert all(group not in first_prompt for group in GROUPS)


def test_parses_markdown_fenced_json_and_maps_labels_to_groups() -> None:
    _, mapping = build_judge_prompt(
        "rubric",
        _query(),
        {group: f"content {index}" for index, group in enumerate(GROUPS)},
    )
    payload = {
        "answers": {
            label: {
                "comprehensiveness": 7,
                "diversity": 6,
                "empowerment": 8,
                "rationale": f"reason for {label}",
            }
            for label in mapping
        }
    }

    parsed = parse_judge_output(
        f"```json\n{json.dumps(payload)}\n```",
        mapping,
    )

    assert set(parsed) == set(GROUPS)
    assert all(item["empowerment"] == 8 for item in parsed.values())


@pytest.mark.parametrize(
    "raw",
    [
        "not json",
        '{"answers":{"Answer-1":{"comprehensiveness":7}}}',
    ],
)
def test_rejects_invalid_or_incomplete_output(raw: str) -> None:
    _, mapping = build_judge_prompt(
        "rubric",
        _query(),
        {group: group for group in GROUPS},
    )

    with pytest.raises(JudgeError):
        parse_judge_output(raw, mapping)


def test_judge_fingerprint_changes_for_material_inputs() -> None:
    query = _query()
    answers = {group: group for group in GROUPS}
    backend = JudgeBackend("gemini", "/bin/gemini", "model-a", "1.0")
    original = judge_fingerprint(query, answers, "rubric-a", backend)

    changed_values = [
        judge_fingerprint(query, {**answers, "naive": "changed"}, "rubric-a", backend),
        judge_fingerprint(query, answers, "rubric-b", backend),
        judge_fingerprint(query, answers, "rubric-a", JudgeBackend("codex", "/bin/codex", "model-a", "1.0")),
        judge_fingerprint(query, answers, "rubric-a", JudgeBackend("gemini", "/bin/gemini", "model-b", "1.0")),
        judge_fingerprint(query, answers, "rubric-a", JudgeBackend("gemini", "/bin/gemini", "model-a", "2.0")),
    ]

    assert all(value != original for value in changed_values)


def test_backend_requires_explicit_model(monkeypatch) -> None:
    monkeypatch.delenv("EVAL_JUDGE_MODEL", raising=False)

    with pytest.raises(JudgeError, match="judge model must be explicit"):
        resolve_backend("gemini")


def _query() -> dict:
    return {
        "query_id": "A-01",
        "category": "A",
        "situation": {"encounter_type": "HEAD_ON"},
        "query": "what should the vessel do?",
    }
