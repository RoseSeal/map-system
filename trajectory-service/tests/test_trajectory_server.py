from __future__ import annotations

import sys
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from inland_traj.geometry import to_local_xy
from scripts import train_gru
from server.trajectory_server.server import RawPoint, feature_matrix, to_predicted_point


def test_feature_matrix_uses_training_feature_path() -> None:
    points = [
        RawPoint(
            msg_time=datetime(2026, 4, 12, 1, 0, 0, tzinfo=timezone.utc),
            latitude=30.0000,
            longitude=114.0000,
            sog=7.5,
            cog=90.0,
        ),
        RawPoint(
            msg_time=datetime(2026, 4, 12, 1, 2, 30, tzinfo=timezone.utc),
            latitude=30.0010,
            longitude=114.0010,
            sog=7.8,
            cog=91.0,
        ),
        RawPoint(
            msg_time=datetime(2026, 4, 12, 1, 5, 0, tzinfo=timezone.utc),
            latitude=30.0020,
            longitude=114.0020,
            sog=8.0,
            cog=92.0,
        ),
    ]
    last = points[-1]
    x, y = to_local_xy(
        np.array([point.longitude for point in points]),
        np.array([point.latitude for point in points]),
        last.longitude,
        last.latitude,
    )
    in_xy = np.stack([x, y], axis=-1)[None, :, :]
    timestamps = np.array([point.msg_time.timestamp() for point in points])
    in_dt = np.diff(timestamps, prepend=timestamps[:1])[None, :]
    in_sog = np.array([[point.sog for point in points]], dtype=float)
    in_cog = np.array([[point.cog for point in points]], dtype=float)

    expected = train_gru.build_features(in_xy, in_dt, in_sog, in_cog)[0]

    np.testing.assert_allclose(feature_matrix(points), expected, rtol=0.0, atol=0.0)


def test_to_predicted_point_round_trips_local_offset_at_anchor() -> None:
    point = to_predicted_point(30.0, 114.0, x_km=0.0, y_km=0.0, offset_seconds=153)

    assert point.latitude == 30.0
    assert point.longitude == 114.0
    assert point.offset_seconds == 153
