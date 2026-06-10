from __future__ import annotations

import re
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

import yaml


OWN_SHIP_ROLES = {"GIVE_WAY", "STAND_ON", "MUTUAL_ACTION", "UNKNOWN", "NOT_APPLICABLE"}
ENCOUNTER_TYPES = {"HEAD_ON", "OVERTAKING", "CROSSING", "UNDEFINED"}
VISIBILITY_VALUES = {"OPEN_VISIBILITY", "RESTRICTED_VISIBILITY", "UNKNOWN"}
WATER_AREAS = {"开阔水域", "限制水域", "狭水道"}
RISK_LEVELS = {"SAFE", "CAUTION", "WARNING", "ALARM"}
OUTCOMES = {"SAFE", "NEAR_MISS", "ACCIDENT"}

REQUIRED_FIELDS = {
    "case_id",
    "title",
    "synthetic",
    "own_ship_role",
    "encounter_type",
    "risk_level",
    "outcome",
    "colregs_rules",
}


class CorpusError(ValueError):
    pass


@dataclass(frozen=True)
class CaseRecord:
    case_id: str
    title: str
    synthetic: bool
    water_area: str
    visibility: str
    own_ship_role: str
    encounter_type: str
    risk_level: str
    own_ship_type: str
    target_ship_type: str
    target_summary: str
    kinematics: str
    colregs_rules: list[str]
    outcome: str
    body: str
    action_digest: str
    lesson: str
    source_file: str

    @property
    def full_text(self) -> str:
        metadata = [
            f"case_id: {self.case_id}",
            f"title: {self.title}",
            f"water_area: {self.water_area}",
            f"visibility: {self.visibility}",
            f"own_ship_role: {self.own_ship_role}",
            f"encounter_type: {self.encounter_type}",
            f"risk_level: {self.risk_level}",
            f"target_summary: {self.target_summary}",
            f"kinematics: {self.kinematics}",
            f"colregs_rules: {', '.join(self.colregs_rules)}",
            f"outcome: {self.outcome}",
        ]
        return "\n".join(metadata) + "\n\n" + self.body

    def to_catalog_entry(self, embedding: list[float]) -> dict[str, Any]:
        payload = asdict(self)
        payload["full_text"] = self.full_text
        payload["embedding"] = embedding
        return payload


def load_cases(cases_dir: Path) -> list[CaseRecord]:
    files = sorted(cases_dir.glob("*.md"))
    if not files:
        raise CorpusError(f"{cases_dir}: no case markdown files found")
    cases = [_parse_case(path) for path in files]
    _validate_unique_case_ids(cases)
    return cases


def _parse_case(path: Path) -> CaseRecord:
    text = path.read_text(encoding="utf-8")
    if not text.startswith("---\n"):
        raise CorpusError(f"{path}: missing YAML front matter")

    try:
        _, raw_meta, body = text.split("---", 2)
    except ValueError as exc:
        raise CorpusError(f"{path}: invalid YAML front matter") from exc

    meta = yaml.safe_load(raw_meta) or {}
    if not isinstance(meta, dict):
        raise CorpusError(f"{path}: YAML front matter must be a mapping")

    _validate_meta(path, meta)
    body = body.strip()
    return CaseRecord(
        case_id=str(meta["case_id"]),
        title=str(meta["title"]),
        synthetic=bool(meta["synthetic"]),
        water_area=str(meta.get("water_area", "")),
        visibility=str(meta.get("visibility", "UNKNOWN")),
        own_ship_role=str(meta["own_ship_role"]),
        encounter_type=str(meta["encounter_type"]),
        risk_level=str(meta["risk_level"]),
        own_ship_type=str(meta.get("own_ship_type", "")),
        target_ship_type=str(meta.get("target_ship_type", "")),
        target_summary=str(meta.get("target_summary", "")),
        kinematics=str(meta.get("kinematics", "")),
        colregs_rules=[str(rule) for rule in meta["colregs_rules"]],
        outcome=str(meta["outcome"]),
        body=body,
        action_digest=_extract_section_digest(body, "处置过程"),
        lesson=_extract_section_digest(body, "经验教训"),
        source_file=path.name,
    )


def _validate_meta(path: Path, meta: dict[str, Any]) -> None:
    missing = sorted(field for field in REQUIRED_FIELDS if field not in meta)
    if missing:
        raise CorpusError(f"{path}: missing required field(s): {', '.join(missing)}")

    if meta["synthetic"] is not True:
        raise CorpusError(f"{path}: synthetic must be true")
    _require_enum(path, "own_ship_role", meta["own_ship_role"], OWN_SHIP_ROLES)
    _require_enum(path, "encounter_type", meta["encounter_type"], ENCOUNTER_TYPES)
    _require_enum(path, "visibility", meta.get("visibility", "UNKNOWN"), VISIBILITY_VALUES)
    _require_enum(path, "risk_level", meta["risk_level"], RISK_LEVELS)
    if meta.get("water_area"):
        _require_enum(path, "water_area", meta["water_area"], WATER_AREAS)
    _require_enum(path, "outcome", meta["outcome"], OUTCOMES)

    rules = meta["colregs_rules"]
    if not isinstance(rules, list) or not rules:
        raise CorpusError(f"{path}: colregs_rules must contain at least one Rule N value")
    for rule in rules:
        if not re.fullmatch(r"Rule \d+[a-zA-Z]?", str(rule)):
            raise CorpusError(f"{path}: colregs_rules contains invalid value '{rule}'")


def _require_enum(path: Path, field: str, value: Any, allowed: set[str]) -> None:
    if str(value) not in allowed:
        values = ", ".join(sorted(allowed))
        raise CorpusError(f"{path}: {field} must be one of: {values}")


def _extract_section_digest(body: str, heading: str) -> str:
    match = re.search(rf"^##\s+{re.escape(heading)}\s*$([\s\S]*?)(?=^##\s+|\Z)", body, re.MULTILINE)
    if not match:
        return ""
    section = match.group(1).strip()
    lines = [re.sub(r"^\s*\d+\.\s*", "", line).strip() for line in section.splitlines()]
    lines = [line for line in lines if line]
    return "，".join(lines)[:160]


def _validate_unique_case_ids(cases: list[CaseRecord]) -> None:
    seen: set[str] = set()
    duplicates: set[str] = set()
    for case in cases:
        if case.case_id in seen:
            duplicates.add(case.case_id)
        seen.add(case.case_id)
    if duplicates:
        raise CorpusError(f"duplicate case_id(s): {', '.join(sorted(duplicates))}")
