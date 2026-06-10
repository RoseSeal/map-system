from __future__ import annotations

from pathlib import Path

import pytest

from graphrag_service.corpus import CorpusError, RISK_LEVELS, load_cases


def test_loads_committed_synthetic_cases() -> None:
    cases = load_cases(Path("data/cases"))

    assert 10 <= len(cases) <= 20
    assert {case.encounter_type for case in cases} >= {"HEAD_ON", "CROSSING", "OVERTAKING"}
    assert all(case.synthetic is True for case in cases)
    assert all(case.risk_level in RISK_LEVELS for case in cases)
    assert all(case.colregs_rules for case in cases)


def test_missing_required_field_reports_file_and_field(tmp_path: Path) -> None:
    case_file = tmp_path / "H-99.md"
    case_file.write_text(
        """---
case_id: H-99
title: 缺少责任字段
synthetic: true
encounter_type: CROSSING
risk_level: WARNING
outcome: SAFE
colregs_rules: [Rule 15]
---

## 处置过程
减速。
""",
        encoding="utf-8",
    )

    with pytest.raises(CorpusError) as exc:
        load_cases(tmp_path)

    message = str(exc.value)
    assert "H-99.md" in message
    assert "own_ship_role" in message


def test_rejects_non_synthetic_case(tmp_path: Path) -> None:
    case_file = tmp_path / "H-98.md"
    case_file.write_text(
        """---
case_id: H-98
title: 非合成案例
synthetic: false
own_ship_role: GIVE_WAY
encounter_type: CROSSING
risk_level: WARNING
outcome: SAFE
colregs_rules: [Rule 15]
---

## 处置过程
减速。
""",
        encoding="utf-8",
    )

    with pytest.raises(CorpusError, match="synthetic must be true"):
        load_cases(tmp_path)


def test_rejects_unknown_risk_level(tmp_path: Path) -> None:
    case_file = tmp_path / "H-97.md"
    case_file.write_text(
        """---
case_id: H-97
title: 非法风险等级
synthetic: true
own_ship_role: GIVE_WAY
encounter_type: CROSSING
risk_level: HIGH
outcome: SAFE
colregs_rules: [Rule 15]
---

## 处置过程
减速。
""",
        encoding="utf-8",
    )

    with pytest.raises(CorpusError, match="risk_level must be one of"):
        load_cases(tmp_path)
