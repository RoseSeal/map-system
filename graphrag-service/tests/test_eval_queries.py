from __future__ import annotations

import json
from collections import Counter
from pathlib import Path

from graphrag_service.corpus import (
    ENCOUNTER_TYPES,
    OWN_SHIP_ROLES,
    RISK_LEVELS,
    VISIBILITY_VALUES,
    WATER_AREAS,
)


QUERIES_PATH = Path("eval/queries.json")
SITUATION_FIELDS = {
    "encounter_type",
    "water_area",
    "risk_level",
    "own_ship_role",
    "visibility",
    "target_count",
}


def test_queries_cover_four_categories_and_pilots() -> None:
    queries = json.loads(QUERIES_PATH.read_text(encoding="utf-8"))

    assert len(queries) == 16
    assert len({query["query_id"] for query in queries}) == 16
    assert Counter(query["category"] for query in queries) == {
        "A": 4,
        "B": 4,
        "C": 4,
        "D": 4,
    }
    assert Counter(query["category"] for query in queries if query["pilot"]) == {
        "A": 1,
        "B": 1,
        "C": 1,
        "D": 1,
    }


def test_query_situations_use_controlled_values() -> None:
    queries = json.loads(QUERIES_PATH.read_text(encoding="utf-8"))

    for query in queries:
        situation = query["situation"]
        assert set(situation) == SITUATION_FIELDS
        assert situation["encounter_type"] in ENCOUNTER_TYPES
        assert situation["water_area"] in WATER_AREAS
        assert situation["risk_level"] in RISK_LEVELS
        assert situation["own_ship_role"] in OWN_SHIP_ROLES
        assert situation["visibility"] in VISIBILITY_VALUES
        assert isinstance(situation["target_count"], int)
        assert situation["target_count"] >= 0
        assert query["query"].strip()
        assert query["notes"].strip()
