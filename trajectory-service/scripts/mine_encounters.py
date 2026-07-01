#!/usr/bin/env python3
"""Mine pairwise encounters and evaluate downstream CPA/TCPA risk errors.

This script is the step-3 downstream bridge: it rebuilds test windows from
``runtime/dataset/segments.parquet``, compares LSQ-CV and tuned GRU predictions
on mined two-vessel encounters, and writes aggregate metrics plus plots under
``runtime/encounters``.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
SCRIPTS = ROOT / "scripts"
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(SCRIPTS))

import torch

import train_gru
from inland_traj.geometry import (
    DANGER_LEVELS,
    cpa_tcpa_numerical,
    cv_predict,
    downstream_risk_error,
    risk_level,
)

K = 10
H = 3
DEFAULT_RADIUS_M = 2.0 * 1852.0
DEFAULT_TIME_WINDOW_S = 90.0


def _safe_nanmean_rows(a: np.ndarray) -> np.ndarray:
    mask = ~np.isnan(a)
    cnt = mask.sum(axis=1)
    summed = np.where(mask, a, 0.0).sum(axis=1)
    return np.where(cnt > 0, summed / np.maximum(cnt, 1), np.nan)


def _feature_matrix(in_xy: np.ndarray, in_dt: np.ndarray, in_sog: np.ndarray, in_cog: np.ndarray) -> np.ndarray:
    """Feature construction kept identical to scripts/train_gru.py::load_split."""
    cog = np.deg2rad(in_cog)
    return np.concatenate(
        [
            in_xy / 1000.0,
            (in_dt / 100.0)[..., None],
            (in_sog / 10.0)[..., None],
            np.sin(cog)[..., None],
            np.cos(cog)[..., None],
        ],
        axis=-1,
    ).astype(np.float32)


def build_anchors(dataset: Path) -> dict[str, np.ndarray | int]:
    cols = ["mmsi", "seg_id", "t", "x", "y", "sog", "cog", "turn_rate_deg_s", "split"]
    df = pd.read_parquet(dataset, columns=cols)
    df = df[df["split"] == "test"]

    chunks: dict[str, list[np.ndarray]] = defaultdict(list)
    segments_with_anchors = 0

    for _, seg in df.groupby("seg_id", sort=False):
        n = len(seg)
        if n < K + H:
            continue

        segments_with_anchors += 1
        mmsi = seg["mmsi"].to_numpy()
        seg_id = seg["seg_id"].to_numpy()
        t = seg["t"].to_numpy(dtype=float)
        x = seg["x"].to_numpy(dtype=float)
        y = seg["y"].to_numpy(dtype=float)
        sog = seg["sog"].to_numpy(dtype=float)
        cog = seg["cog"].to_numpy(dtype=float)
        turn = seg["turn_rate_deg_s"].to_numpy(dtype=float)

        starts = np.arange(0, n - K - H + 1)
        in_idx = starts[:, None] + np.arange(K)
        tgt_idx = starts[:, None] + (K + np.arange(H))
        last = in_idx[:, -1]

        x0 = x[last]
        y0 = y[last]
        hist_xy_abs = np.stack([x[in_idx], y[in_idx]], axis=-1)
        in_xy = np.stack([x[in_idx] - x0[:, None], y[in_idx] - y0[:, None]], axis=-1)
        in_dt = np.diff(t[in_idx], axis=1, prepend=t[in_idx][:, :1])
        in_sog = np.nan_to_num(sog[in_idx], nan=0.0)
        in_cog = np.nan_to_num(cog[in_idx], nan=0.0)
        fut_xy = np.stack([x[tgt_idx], y[tgt_idx]], axis=-1)
        fut_t = t[tgt_idx]
        horizons_s = fut_t - t[last, None]

        chunks["mmsi"].append(mmsi[last].astype(np.int64))
        chunks["seg_id"].append(seg_id[last].astype(np.int64))
        chunks["anchor_offset"].append(starts.astype(np.int64))
        chunks["t0"].append(t[last].astype(np.float64))
        chunks["x0"].append(x0.astype(np.float64))
        chunks["y0"].append(y0.astype(np.float64))
        chunks["hist_xy_abs"].append(hist_xy_abs.astype(np.float32))
        chunks["hist_t"].append(t[in_idx].astype(np.float64))
        chunks["fut_xy"].append(fut_xy.astype(np.float32))
        chunks["fut_t"].append(fut_t.astype(np.float64))
        chunks["horizons_s"].append(horizons_s.astype(np.float32))
        chunks["turn"].append(_safe_nanmean_rows(turn[tgt_idx]).astype(np.float32))
        chunks["features"].append(_feature_matrix(in_xy, in_dt, in_sog, in_cog))

    if not chunks:
        raise RuntimeError("No test anchors found; need test segments with at least 13 points.")

    anchors = {key: np.concatenate(value, axis=0) for key, value in chunks.items()}
    anchors["segments_with_anchors"] = segments_with_anchors
    return anchors


def check_feature_alignment(
    anchors: dict[str, np.ndarray | int],
    windows_test: Path | None,
    sample_n: int,
) -> dict[str, Any]:
    if windows_test is None or not windows_test.exists():
        return {"status": "skipped", "reason": "test windows npz not found"}

    try:
        x_ref, _, _, _ = train_gru.load_split(windows_test)
    except Exception as exc:  # pragma: no cover - diagnostic path
        return {"status": "failed", "reason": f"train_gru.load_split failed: {exc}"}

    features = anchors["features"]
    assert isinstance(features, np.ndarray)
    if len(x_ref) != len(features):
        return {
            "status": "skipped",
            "reason": f"anchor count {len(features)} != windows count {len(x_ref)}",
        }

    n = min(sample_n, len(features))
    idx = np.linspace(0, len(features) - 1, n, dtype=np.int64)
    diff = np.max(np.abs(features[idx] - x_ref.numpy()[idx])) if n else 0.0
    return {
        "status": "passed" if diff < 1e-6 else "failed",
        "sampled": int(n),
        "max_abs_diff": float(diff),
        "source": "scripts/train_gru.py::load_split",
    }


def predict_cv(anchors: dict[str, np.ndarray | int]) -> np.ndarray:
    hist_xy = anchors["hist_xy_abs"]
    hist_t = anchors["hist_t"]
    horizons = anchors["horizons_s"]
    assert isinstance(hist_xy, np.ndarray)
    assert isinstance(hist_t, np.ndarray)
    assert isinstance(horizons, np.ndarray)

    out = np.empty((len(hist_xy), H, 2), dtype=np.float32)
    for i in range(len(hist_xy)):
        out[i] = cv_predict(hist_xy[i], hist_t[i], horizons[i], window=3).astype(np.float32)
    return out


def predict_gru(
    anchors: dict[str, np.ndarray | int],
    model_path: Path,
    hidden: int,
    layers: int,
    batch_size: int,
    device_name: str,
) -> np.ndarray:
    device = torch.device(device_name)
    model = train_gru.GRUPredictor(in_dim=6, hidden=hidden, layers=layers).to(device)
    state = torch.load(model_path, map_location=device)
    model.load_state_dict(state)
    model.eval()

    features = anchors["features"]
    x0 = anchors["x0"]
    y0 = anchors["y0"]
    assert isinstance(features, np.ndarray)
    assert isinstance(x0, np.ndarray)
    assert isinstance(y0, np.ndarray)

    pred = np.empty((len(features), H, 2), dtype=np.float32)
    with torch.no_grad():
        for start in range(0, len(features), batch_size):
            stop = min(start + batch_size, len(features))
            xb = torch.from_numpy(features[start:stop]).to(device)
            pred_km = model(xb).cpu().numpy()
            pred[start:stop] = pred_km * 1000.0

    origin = np.stack([x0, y0], axis=-1).astype(np.float32)
    return pred + origin[:, None, :]


def mine_pairs(
    anchors: dict[str, np.ndarray | int],
    time_window_s: float,
    radius_m: float,
    max_encounters: int | None,
) -> tuple[np.ndarray, np.ndarray]:
    mmsi = anchors["mmsi"]
    t0 = anchors["t0"]
    x0 = anchors["x0"]
    y0 = anchors["y0"]
    assert isinstance(mmsi, np.ndarray)
    assert isinstance(t0, np.ndarray)
    assert isinstance(x0, np.ndarray)
    assert isinstance(y0, np.ndarray)

    time_bin = np.floor(t0 / time_window_s).astype(np.int64)
    gx = np.floor(x0 / radius_m).astype(np.int64)
    gy = np.floor(y0 / radius_m).astype(np.int64)

    cells: dict[tuple[int, int, int], list[int]] = defaultdict(list)
    for i, key in enumerate(zip(time_bin, gx, gy)):
        cells[key].append(i)

    a_idx: list[int] = []
    b_idx: list[int] = []
    radius2 = radius_m * radius_m

    for i in range(len(t0)):
        for dtb in (-1, 0, 1):
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    for j in cells.get((time_bin[i] + dtb, gx[i] + dx, gy[i] + dy), ()):
                        if j <= i or mmsi[j] == mmsi[i]:
                            continue
                        if abs(t0[i] - t0[j]) >= time_window_s:
                            continue
                        dist2 = (x0[i] - x0[j]) ** 2 + (y0[i] - y0[j]) ** 2
                        if dist2 >= radius2:
                            continue
                        a_idx.append(i)
                        b_idx.append(j)
                        if max_encounters is not None and len(a_idx) >= max_encounters:
                            return np.asarray(a_idx, dtype=np.int64), np.asarray(b_idx, dtype=np.int64)

    return np.asarray(a_idx, dtype=np.int64), np.asarray(b_idx, dtype=np.int64)


def _common_grid(t_a: np.ndarray, t_b: np.ndarray, step_s: float) -> np.ndarray | None:
    start = max(float(t_a[0]), float(t_b[0]))
    end = min(float(t_a[-1]), float(t_b[-1]))
    if not math.isfinite(start) or not math.isfinite(end) or end <= start:
        return None

    grid = np.arange(start, end + 1e-6, step_s, dtype=float)
    if len(grid) == 0 or grid[-1] < end:
        grid = np.append(grid, end)
    if len(grid) < 2:
        grid = np.asarray([start, end], dtype=float)
    return grid


def _interp_traj(times: np.ndarray, xy: np.ndarray, grid: np.ndarray) -> np.ndarray:
    return np.column_stack(
        [
            np.interp(grid, times, xy[:, 0]),
            np.interp(grid, times, xy[:, 1]),
        ]
    )


def _risk_counts(values: np.ndarray) -> dict[str, int]:
    c = Counter(str(v) for v in values)
    return {key: int(c.get(key, 0)) for key in ("ALARM", "WARNING", "CAUTION", "SAFE")}


def _stats(values: np.ndarray) -> dict[str, float | None]:
    if len(values) == 0:
        return {key: None for key in ("mean", "median", "p75", "p90", "max")}
    return {
        "mean": round(float(np.mean(values)), 3),
        "median": round(float(np.median(values)), 3),
        "p75": round(float(np.percentile(values, 75)), 3),
        "p90": round(float(np.percentile(values, 90)), 3),
        "max": round(float(np.max(values)), 3),
    }


def _rate(numer: int | float, denom: int | float) -> float | None:
    if denom == 0:
        return None
    return round(float(numer) / float(denom), 6)


def _model_summary(
    data: dict[str, np.ndarray],
    model: str,
    mask: np.ndarray,
    true_danger: np.ndarray,
) -> dict[str, Any]:
    n = int(mask.sum())
    true_safe = mask & ~true_danger
    true_danger_mask = mask & true_danger

    fa = data[f"{model}_false_alarm"][mask]
    miss = data[f"{model}_miss"][mask]
    match = data[f"{model}_risk_match"][mask]
    risk_pred = data[f"{model}_risk_pred"][mask]

    false_alarm_count = int(fa.sum())
    miss_count = int(miss.sum())
    return {
        "dcpa_err_m": _stats(data[f"{model}_dcpa_err"][mask]),
        "tcpa_err_s": _stats(data[f"{model}_tcpa_err"][mask]),
        "risk_match_rate": _rate(int(match.sum()), n),
        "risk_pred_counts": _risk_counts(risk_pred),
        "false_alarm_count": false_alarm_count,
        "false_alarm_rate": _rate(false_alarm_count, n),
        "false_alarm_rate_among_true_safe": _rate(false_alarm_count, int(true_safe.sum())),
        "miss_count": miss_count,
        "miss_rate": _rate(miss_count, n),
        "miss_rate_among_true_danger": _rate(miss_count, int(true_danger_mask.sum())),
    }


def _comparison_summary(bucket: dict[str, Any]) -> dict[str, float | None]:
    cv = bucket["cv"]
    gru = bucket["gru"]
    cv_dcpa = cv["dcpa_err_m"]["mean"]
    gru_dcpa = gru["dcpa_err_m"]["mean"]
    cv_tcpa = cv["tcpa_err_s"]["mean"]
    gru_tcpa = gru["tcpa_err_s"]["mean"]
    return {
        "dcpa_mean_err_reduction_m": None if cv_dcpa is None or gru_dcpa is None else round(cv_dcpa - gru_dcpa, 3),
        "dcpa_mean_err_reduction_pct": None
        if cv_dcpa in (None, 0)
        else round((cv_dcpa - gru_dcpa) / cv_dcpa * 100.0, 3),
        "tcpa_mean_err_reduction_s": None if cv_tcpa is None or gru_tcpa is None else round(cv_tcpa - gru_tcpa, 3),
        "tcpa_mean_err_reduction_pct": None
        if cv_tcpa in (None, 0)
        else round((cv_tcpa - gru_tcpa) / cv_tcpa * 100.0, 3),
        "risk_match_rate_delta_gru_minus_cv": None
        if cv["risk_match_rate"] is None or gru["risk_match_rate"] is None
        else round(gru["risk_match_rate"] - cv["risk_match_rate"], 6),
        "false_alarm_rate_reduction": None
        if cv["false_alarm_rate"] is None or gru["false_alarm_rate"] is None
        else round(cv["false_alarm_rate"] - gru["false_alarm_rate"], 6),
        "miss_rate_reduction": None
        if cv["miss_rate"] is None or gru["miss_rate"] is None
        else round(cv["miss_rate"] - gru["miss_rate"], 6),
    }


def summarize_metrics(data: dict[str, np.ndarray], turn_p75: float) -> dict[str, Any]:
    n = len(data["true_risk"])
    all_mask = np.ones(n, dtype=bool)
    bend_mask = data["is_bend_encounter"]
    non_bend_mask = ~bend_mask
    true_danger = np.isin(data["true_risk"], list(DANGER_LEVELS))

    def bucket(mask: np.ndarray) -> dict[str, Any]:
        out = {
            "encounters": int(mask.sum()),
            "true_risk_counts": _risk_counts(data["true_risk"][mask]),
            "true_danger_rate": _rate(int((mask & true_danger).sum()), int(mask.sum())),
            "true_dcpa_m": _stats(data["true_dcpa"][mask]),
            "true_tcpa_s": _stats(data["true_tcpa"][mask]),
            "cv": _model_summary(data, "cv", mask, true_danger),
            "gru": _model_summary(data, "gru", mask, true_danger),
        }
        out["cv_vs_gru"] = _comparison_summary(out)
        return out

    return {
        "turn_p75_deg_s": round(float(turn_p75), 6),
        "global": bucket(all_mask),
        "buckets": {
            "bend": bucket(bend_mask),
            "non_bend": bucket(non_bend_mask),
        },
    }


def evaluate_pairs(
    anchors: dict[str, np.ndarray | int],
    cv_pred: np.ndarray,
    gru_pred: np.ndarray,
    a_idx: np.ndarray,
    b_idx: np.ndarray,
    grid_step_s: float,
    turn_p75: float,
) -> tuple[dict[str, np.ndarray], int]:
    fut_xy = anchors["fut_xy"]
    fut_t = anchors["fut_t"]
    t0 = anchors["t0"]
    turn = anchors["turn"]
    assert isinstance(fut_xy, np.ndarray)
    assert isinstance(fut_t, np.ndarray)
    assert isinstance(t0, np.ndarray)
    assert isinstance(turn, np.ndarray)

    rows: dict[str, list[Any]] = defaultdict(list)
    skipped = 0

    for a, b in zip(a_idx, b_idx):
        grid = _common_grid(fut_t[a], fut_t[b], grid_step_s)
        if grid is None:
            skipped += 1
            continue

        times_rel = grid - max(float(t0[a]), float(t0[b]))
        if np.any(np.diff(times_rel) <= 0):
            skipped += 1
            continue

        gt_a = _interp_traj(fut_t[a], fut_xy[a], grid)
        gt_b = _interp_traj(fut_t[b], fut_xy[b], grid)
        cv_a = _interp_traj(fut_t[a], cv_pred[a], grid)
        cv_b = _interp_traj(fut_t[b], cv_pred[b], grid)
        gru_a = _interp_traj(fut_t[a], gru_pred[a], grid)
        gru_b = _interp_traj(fut_t[b], gru_pred[b], grid)

        cv_err = downstream_risk_error(cv_a, cv_b, gt_a, gt_b, times_rel)
        gru_err = downstream_risk_error(gru_a, gru_b, gt_a, gt_b, times_rel)
        true_dcpa = float(cv_err["dcpa_gt"])
        true_tcpa = float(cv_err["tcpa_gt"])
        true_risk = str(cv_err["risk_gt"])

        is_bend = (
            (not np.isnan(turn[a]) and turn[a] >= turn_p75)
            or (not np.isnan(turn[b]) and turn[b] >= turn_p75)
        )

        rows["a_idx"].append(int(a))
        rows["b_idx"].append(int(b))
        rows["grid_start_epoch_s"].append(float(grid[0]))
        rows["grid_end_epoch_s"].append(float(grid[-1]))
        rows["true_dcpa"].append(true_dcpa)
        rows["true_tcpa"].append(true_tcpa)
        rows["true_risk"].append(true_risk)
        rows["is_bend_encounter"].append(bool(is_bend))

        for model, err in (("cv", cv_err), ("gru", gru_err)):
            rows[f"{model}_dcpa_pred"].append(float(err["dcpa_pred"]))
            rows[f"{model}_tcpa_pred"].append(float(err["tcpa_pred"]))
            rows[f"{model}_dcpa_err"].append(float(err["dcpa_err"]))
            rows[f"{model}_tcpa_err"].append(float(err["tcpa_err"]))
            rows[f"{model}_risk_pred"].append(str(err["risk_pred"]))
            rows[f"{model}_risk_match"].append(bool(err["risk_match"]))
            rows[f"{model}_false_alarm"].append(bool(err["false_alarm"]))
            rows[f"{model}_miss"].append(bool(err["miss"]))

    arrays: dict[str, np.ndarray] = {}
    for key, value in rows.items():
        if key.endswith("_risk") or key.endswith("_risk_pred"):
            arrays[key] = np.asarray(value, dtype=object)
        elif key in {"is_bend_encounter", "cv_risk_match", "cv_false_alarm", "cv_miss",
                     "gru_risk_match", "gru_false_alarm", "gru_miss"}:
            arrays[key] = np.asarray(value, dtype=bool)
        elif key in {"a_idx", "b_idx"}:
            arrays[key] = np.asarray(value, dtype=np.int64)
        else:
            arrays[key] = np.asarray(value, dtype=float)
    return arrays, skipped


def _closest_points(traj_a: np.ndarray, traj_b: np.ndarray, times_rel: np.ndarray, tcpa_s: float) -> tuple[np.ndarray, np.ndarray]:
    t = float(np.clip(tcpa_s, times_rel[0], times_rel[-1]))
    ax = np.interp(t, times_rel, traj_a[:, 0])
    ay = np.interp(t, times_rel, traj_a[:, 1])
    bx = np.interp(t, times_rel, traj_b[:, 0])
    by = np.interp(t, times_rel, traj_b[:, 1])
    return np.asarray([ax, ay]), np.asarray([bx, by])


def plot_examples(
    out_dir: Path,
    anchors: dict[str, np.ndarray | int],
    cv_pred: np.ndarray,
    gru_pred: np.ndarray,
    data: dict[str, np.ndarray],
    count: int,
    grid_step_s: float,
) -> list[str]:
    if count <= 0 or len(data["true_risk"]) == 0:
        return []

    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    out_dir.mkdir(parents=True, exist_ok=True)
    fut_xy = anchors["fut_xy"]
    fut_t = anchors["fut_t"]
    hist_xy = anchors["hist_xy_abs"]
    t0 = anchors["t0"]
    mmsi = anchors["mmsi"]
    assert isinstance(fut_xy, np.ndarray)
    assert isinstance(fut_t, np.ndarray)
    assert isinstance(hist_xy, np.ndarray)
    assert isinstance(t0, np.ndarray)
    assert isinstance(mmsi, np.ndarray)

    true_danger = np.isin(data["true_risk"], list(DANGER_LEVELS))
    improvement = data["cv_dcpa_err"] - data["gru_dcpa_err"]
    rank_score = improvement + true_danger.astype(float) * 1e6 + data["is_bend_encounter"].astype(float) * 1e5
    order = np.argsort(-rank_score)[:count]

    paths: list[str] = []
    for plot_no, row in enumerate(order, start=1):
        a = int(data["a_idx"][row])
        b = int(data["b_idx"][row])
        grid = _common_grid(fut_t[a], fut_t[b], grid_step_s)
        if grid is None:
            continue
        times_rel = grid - max(float(t0[a]), float(t0[b]))

        trajs = {
            "true": (
                _interp_traj(fut_t[a], fut_xy[a], grid),
                _interp_traj(fut_t[b], fut_xy[b], grid),
                "-",
                "black",
            ),
            "cv": (
                _interp_traj(fut_t[a], cv_pred[a], grid),
                _interp_traj(fut_t[b], cv_pred[b], grid),
                "--",
                "tab:red",
            ),
            "gru": (
                _interp_traj(fut_t[a], gru_pred[a], grid),
                _interp_traj(fut_t[b], gru_pred[b], grid),
                ":",
                "tab:green",
            ),
        }

        fig, ax = plt.subplots(figsize=(8, 7), dpi=140)
        ax.plot(hist_xy[a, :, 0], hist_xy[a, :, 1], color="tab:blue", alpha=0.25, linewidth=1.5)
        ax.plot(hist_xy[b, :, 0], hist_xy[b, :, 1], color="tab:orange", alpha=0.25, linewidth=1.5)
        ax.scatter(hist_xy[a, -1, 0], hist_xy[a, -1, 1], color="tab:blue", s=30, marker="o", label="A history")
        ax.scatter(hist_xy[b, -1, 0], hist_xy[b, -1, 1], color="tab:orange", s=30, marker="o", label="B history")

        for name, (ta, tb, style, cpa_color) in trajs.items():
            ax.plot(ta[:, 0], ta[:, 1], linestyle=style, color="tab:blue", linewidth=2.0, label=f"A {name}")
            ax.plot(tb[:, 0], tb[:, 1], linestyle=style, color="tab:orange", linewidth=2.0, label=f"B {name}")
            dcpa, tcpa = cpa_tcpa_numerical(ta, tb, times_rel)
            pa, pb = _closest_points(ta, tb, times_rel, tcpa)
            ax.plot([pa[0], pb[0]], [pa[1], pb[1]], color=cpa_color, alpha=0.55, linewidth=1.2)
            mid = (pa + pb) / 2.0
            ax.text(mid[0], mid[1], f"{name} {dcpa:.0f}m/{tcpa:.0f}s", fontsize=7, color=cpa_color)

        title = (
            f"encounter {plot_no}: MMSI {mmsi[a]} vs {mmsi[b]} | "
            f"truth {data['true_risk'][row]} "
            f"DCPA={data['true_dcpa'][row]:.0f}m TCPA={data['true_tcpa'][row]:.0f}s"
        )
        ax.set_title(title, fontsize=9)
        ax.set_xlabel("x east (m)")
        ax.set_ylabel("y north (m)")
        ax.axis("equal")
        ax.grid(True, alpha=0.25)
        ax.legend(loc="best", fontsize=7, ncol=2)
        fig.tight_layout()

        path = out_dir / f"encounter_{plot_no:02d}.png"
        fig.savefig(path)
        plt.close(fig)
        paths.append(str(path))

    return paths


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dataset", default="runtime/dataset/segments.parquet")
    ap.add_argument("--model", default="runtime/gru_tuned/gru.pt")
    ap.add_argument("--out", default="runtime/encounters")
    ap.add_argument("--windows-test", default="runtime/windows/test.npz")
    ap.add_argument("--hidden", type=int, default=128)
    ap.add_argument("--layers", type=int, default=2)
    ap.add_argument("--batch-size", type=int, default=8192)
    ap.add_argument("--device", default="cpu")
    ap.add_argument("--time-window-s", type=float, default=DEFAULT_TIME_WINDOW_S)
    ap.add_argument("--radius-m", type=float, default=DEFAULT_RADIUS_M)
    ap.add_argument("--grid-step-s", type=float, default=30.0)
    ap.add_argument("--plots", type=int, default=8)
    ap.add_argument("--max-encounters", type=int, default=None)
    ap.add_argument("--feature-check-samples", type=int, default=256)
    return ap.parse_args()


def main() -> None:
    args = parse_args()
    dataset = Path(args.dataset)
    model_path = Path(args.model)
    out = Path(args.out)
    plots_dir = out / "plots"

    print(f"[encounters] loading anchors from {dataset}")
    anchors = build_anchors(dataset)
    anchor_count = len(anchors["mmsi"])
    print(f"[encounters] anchors={anchor_count:,} test_segments={anchors['segments_with_anchors']:,}")

    feature_check = check_feature_alignment(
        anchors,
        Path(args.windows_test) if args.windows_test else None,
        args.feature_check_samples,
    )
    print(f"[encounters] feature_check={feature_check['status']}")

    turns = anchors["turn"]
    assert isinstance(turns, np.ndarray)
    turn_p75 = float(np.nanpercentile(turns, 75))

    print("[encounters] predicting CV with geometry.cv_predict(window=3)")
    cv_pred = predict_cv(anchors)
    print(f"[encounters] predicting GRU from {model_path}")
    gru_pred = predict_gru(
        anchors,
        model_path,
        args.hidden,
        args.layers,
        args.batch_size,
        args.device,
    )

    print(
        f"[encounters] mining pairs | dt<{args.time_window_s:g}s "
        f"distance<{args.radius_m:g}m"
    )
    a_idx, b_idx = mine_pairs(anchors, args.time_window_s, args.radius_m, args.max_encounters)
    print(f"[encounters] mined_pairs={len(a_idx):,}")

    data, skipped = evaluate_pairs(
        anchors,
        cv_pred,
        gru_pred,
        a_idx,
        b_idx,
        args.grid_step_s,
        turn_p75,
    )
    if len(data.get("true_risk", [])) == 0:
        raise RuntimeError("No evaluable encounters after common-grid filtering.")
    print(f"[encounters] evaluated={len(data['true_risk']):,} skipped_no_overlap={skipped:,}")

    metrics = {
        "inputs": {
            "dataset": str(dataset),
            "model": str(model_path),
            "windows_test": str(args.windows_test) if args.windows_test else None,
        },
        "parameters": {
            "history_points": K,
            "future_points": H,
            "time_window_s": args.time_window_s,
            "radius_m": args.radius_m,
            "radius_nm": args.radius_m / 1852.0,
            "grid_step_s": args.grid_step_s,
            "cv_window_points": 3,
            "gru_hidden": args.hidden,
            "gru_layers": args.layers,
            "max_encounters": args.max_encounters,
        },
        "counts": {
            "test_anchor_count": int(anchor_count),
            "test_segments_with_anchors": int(anchors["segments_with_anchors"]),
            "mined_encounter_count": int(len(a_idx)),
            "evaluated_encounter_count": int(len(data["true_risk"])),
            "skipped_no_common_grid": int(skipped),
        },
        "feature_alignment_check": feature_check,
        "metrics": summarize_metrics(data, turn_p75),
    }

    plot_paths = plot_examples(plots_dir, anchors, cv_pred, gru_pred, data, args.plots, args.grid_step_s)
    metrics["plots"] = plot_paths
    write_json(out / "metrics.json", metrics)
    print(f"[encounters] wrote {out / 'metrics.json'}")
    if plot_paths:
        print(f"[encounters] wrote {len(plot_paths)} plots under {plots_dir}")


if __name__ == "__main__":
    main()
