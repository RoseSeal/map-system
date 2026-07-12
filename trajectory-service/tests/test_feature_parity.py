"""Offline-vs-online projection-origin parity on real windows.

The offline pipeline (clean_segment) projects lat/lon with a fixed dataset
origin and build_windows subtracts the window-last point; the online sidecar
projects directly with the window-last point as origin. The two differ by a
cos(latitude) scale factor. This test bounds that difference — on features
and on end-to-end GRU predictions — against thresholds well below the effect
size the model is deployed for (FDE 224.9 m, CV-GRU gap 88.9 m).

Requires gitignored runtime artifacts; skipped when they are absent.
Measured baseline (500 windows, 2026-07-10 acceptance): input xy delta
max 30.8 m / p50 3.8 m; prediction delta max 15.3 m / p50 0.7 m.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from inland_traj.features import build_features
from inland_traj.geometry import to_local_xy

SEGMENTS = ROOT / "runtime" / "dataset" / "segments.parquet"
ONNX_MODEL = ROOT / "runtime" / "gru_tuned" / "gru.onnx"

WINDOW_SIZE = 10
SAMPLE_WINDOWS = 200
INPUT_XY_DELTA_MAX_M = 60.0
PREDICTION_DELTA_MAX_M = 30.0


@pytest.mark.skipif(
    not (SEGMENTS.exists() and ONNX_MODEL.exists()),
    reason="requires gitignored runtime dataset and exported ONNX model",
)
def test_projection_origin_parity_on_real_windows() -> None:
    import onnxruntime as ort
    import pandas as pd

    df = pd.read_parquet(SEGMENTS)
    segs = [g for _, g in df[df.split == "test"].groupby("seg_id", sort=False) if len(g) >= WINDOW_SIZE]
    rng = np.random.default_rng(0)
    sample = rng.choice(len(segs), size=min(SAMPLE_WINDOWS, len(segs)), replace=False)

    session = ort.InferenceSession(str(ONNX_MODEL), providers=["CPUExecutionProvider"])
    input_xy_delta_m: list[float] = []
    prediction_delta_m: list[float] = []
    for i in sample:
        seg = segs[i].head(WINDOW_SIZE)
        t = seg.t.to_numpy(float)
        dt = np.diff(t, prepend=t[:1])[None, :]
        sog = seg.sog.to_numpy(float)[None, :]
        cog = seg.cog.to_numpy(float)[None, :]

        x_off = seg.x.to_numpy()
        y_off = seg.y.to_numpy()
        offline_xy = np.stack([x_off - x_off[-1], y_off - y_off[-1]], axis=-1)[None, :, :]

        x_on, y_on = to_local_xy(seg.lon.to_numpy(), seg.lat.to_numpy(), seg.lon.iloc[-1], seg.lat.iloc[-1])
        online_xy = np.stack([x_on, y_on], axis=-1)[None, :, :]

        input_xy_delta_m.append(float(np.max(np.abs(offline_xy - online_xy))))

        pred_offline = session.run(None, {"features": build_features(offline_xy, dt, sog, cog)})[0]
        pred_online = session.run(None, {"features": build_features(online_xy, dt, sog, cog)})[0]
        prediction_delta_m.append(float(np.max(np.linalg.norm(pred_offline - pred_online, axis=-1)) * 1000.0))

    assert max(input_xy_delta_m) < INPUT_XY_DELTA_MAX_M
    assert max(prediction_delta_m) < PREDICTION_DELTA_MAX_M
