#!/usr/bin/env python3
"""Generate presentation figures from existing runtime artifacts.

The script intentionally reuses the project training and encounter helpers so
figure generation does not introduce a second metric or prediction dialect:

* GRU features are checked against ``train_gru.load_split``.
* CV predictions go through ``geometry.cv_predict(..., window=3)`` via
  ``mine_encounters.predict_cv``.
* Encounter risk labels use the same CPA/TCPA evaluation path as
  ``scripts/mine_encounters.py``.
"""
from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
SCRIPTS = ROOT / "scripts"
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(SCRIPTS))

import train_gru
from inland_traj.geometry import DANGER_LEVELS, NM, cpa_tcpa_numerical, risk_level
from mine_encounters import (
    DEFAULT_RADIUS_M,
    DEFAULT_TIME_WINDOW_S,
    _closest_points,
    _common_grid,
    _interp_traj,
    build_anchors,
    evaluate_pairs,
    mine_pairs,
    predict_cv,
    predict_gru,
)

K = 10
H = 3
HORIZON_LABELS = ["+2.5 min", "+5 min", "+7.5 min"]


@dataclass(frozen=True)
class BendSelection:
    idx: int
    turn_rate_deg_s: float
    cv_fde_m: float
    gru_fde_m: float
    heading_change_deg: float
    step_ratio_min: float
    step_ratio_max: float
    future_path_m: float
    future_chord_m: float
    curvature_ratio: float
    score: float


@dataclass(frozen=True)
class EncounterSelection:
    row: int
    a_idx: int
    b_idx: int
    exact_match: bool
    heading_angle_deg: float
    plot_span_m: float
    score: float


def parse_args() -> argparse.Namespace:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dataset", default="runtime/dataset/segments.parquet")
    ap.add_argument("--windows-test", default="runtime/windows/test.npz")
    ap.add_argument("--model", default="runtime/gru_tuned/gru.pt")
    ap.add_argument("--out", default="runtime/figures")
    ap.add_argument("--hidden", type=int, default=128)
    ap.add_argument("--layers", type=int, default=2)
    ap.add_argument("--batch-size", type=int, default=8192)
    ap.add_argument("--device", default="cpu")
    ap.add_argument("--time-window-s", type=float, default=DEFAULT_TIME_WINDOW_S)
    ap.add_argument("--radius-m", type=float, default=DEFAULT_RADIUS_M)
    ap.add_argument("--grid-step-s", type=float, default=30.0)
    ap.add_argument("--max-encounters", type=int, default=None)
    ap.add_argument("--feature-check-samples", type=int, default=512)
    ap.add_argument("--dpi", type=int, default=180)
    ap.add_argument("--no-gif", action="store_true")
    ap.add_argument("--with-metrics", action="store_true", help="Regenerate fig_metrics.png. Default keeps the existing accepted file untouched.")
    ap.add_argument(
        "--allow-fallback-encounter",
        action="store_true",
        help="For smoke tests only: plot the best available miss if no exact CV-safe/GRU-danger case is in the sample.",
    )
    return ap.parse_args()


def setup_matplotlib():
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    plt.rcParams.update(
        {
            "font.size": 13,
            "axes.titlesize": 17,
            "axes.labelsize": 14,
            "xtick.labelsize": 12,
            "ytick.labelsize": 12,
            "legend.fontsize": 11,
            "figure.titlesize": 18,
            "lines.linewidth": 2.6,
            "axes.grid": True,
            "grid.alpha": 0.22,
            "savefig.facecolor": "white",
            "font.sans-serif": [
                "PingFang SC",
                "Arial Unicode MS",
                "Heiti TC",
                "SimHei",
                "DejaVu Sans",
            ],
        }
    )
    return plt


def check_feature_alignment(
    anchors: dict[str, np.ndarray | int],
    windows_test: Path,
    sample_n: int,
) -> dict[str, Any]:
    """Compare rebuilt anchor features with the canonical train_gru loader."""
    features = anchors["features"]
    assert isinstance(features, np.ndarray)
    x_ref, _, _, _ = train_gru.load_split(windows_test)

    if len(features) != len(x_ref):
        return {
            "status": "failed",
            "reason": f"anchor count {len(features)} != windows count {len(x_ref)}",
        }

    n = min(sample_n, len(features))
    idx = np.linspace(0, len(features) - 1, n, dtype=np.int64) if n else np.asarray([], dtype=np.int64)
    diff = float(np.max(np.abs(features[idx] - x_ref.numpy()[idx]))) if n else 0.0
    return {
        "status": "passed" if diff < 1e-6 else "failed",
        "sampled": int(n),
        "max_abs_diff": diff,
        "source": "scripts/train_gru.py::load_split",
    }


def _angle_delta_deg(values: np.ndarray) -> np.ndarray:
    return (values + 180.0) % 360.0 - 180.0


def _segment_headings_deg(points: np.ndarray) -> np.ndarray:
    delta = np.diff(points, axis=-2)
    return np.degrees(np.arctan2(delta[..., 1], delta[..., 0]))


def _trajectory_heading_angle_deg(traj_a: np.ndarray, traj_b: np.ndarray) -> float:
    va = np.asarray(traj_a[-1] - traj_a[0], dtype=float)
    vb = np.asarray(traj_b[-1] - traj_b[0], dtype=float)
    na = float(np.linalg.norm(va))
    nb = float(np.linalg.norm(vb))
    if na <= 1e-9 or nb <= 1e-9:
        return float("nan")
    cos = float(np.clip((va @ vb) / (na * nb), -1.0, 1.0))
    return float(np.degrees(np.arccos(cos)))


def bend_geometry_metrics(
    hist_xy: np.ndarray,
    fut_xy: np.ndarray,
) -> dict[str, np.ndarray]:
    """Geometry-only bend evidence from the true future path."""
    points = np.concatenate([hist_xy[:, -1:, :], fut_xy], axis=1)
    headings = _segment_headings_deg(points)
    heading_change = np.sum(np.abs(_angle_delta_deg(np.diff(headings, axis=1))), axis=1)

    future_steps = np.linalg.norm(np.diff(points, axis=1), axis=2)
    recent_steps = np.linalg.norm(np.diff(hist_xy[:, -4:, :], axis=1), axis=2)
    ratios = future_steps / np.maximum(recent_steps, 1e-6)

    future_path = future_steps.sum(axis=1)
    future_chord = np.linalg.norm(fut_xy[:, -1, :] - hist_xy[:, -1, :], axis=1)
    curvature_ratio = future_path / np.maximum(future_chord, 1e-6)

    return {
        "heading_change_deg": heading_change,
        "step_ratio_min": np.min(ratios, axis=1),
        "step_ratio_max": np.max(ratios, axis=1),
        "future_path_m": future_path,
        "future_chord_m": future_chord,
        "curvature_ratio": curvature_ratio,
    }


def select_bend_window(
    anchors: dict[str, np.ndarray | int],
    cv_pred: np.ndarray,
    gru_pred: np.ndarray,
) -> BendSelection:
    hist_xy = anchors["hist_xy_abs"]
    fut_xy = anchors["fut_xy"]
    turn = anchors["turn"]
    assert isinstance(hist_xy, np.ndarray)
    assert isinstance(fut_xy, np.ndarray)
    assert isinstance(turn, np.ndarray)

    geom = bend_geometry_metrics(hist_xy, fut_xy)
    cv_fde = np.linalg.norm(cv_pred[:, -1, :] - fut_xy[:, -1, :], axis=1)
    gru_fde = np.linalg.norm(gru_pred[:, -1, :] - fut_xy[:, -1, :], axis=1)
    gain = cv_fde - gru_fde
    valid = (
        np.isfinite(turn)
        & np.isfinite(cv_fde)
        & np.isfinite(gru_fde)
        & np.isfinite(geom["heading_change_deg"])
        & (geom["heading_change_deg"] >= 25.0)
        & (geom["step_ratio_min"] >= 0.6)
        & (geom["step_ratio_max"] <= 1.4)
        & (cv_fde >= 400.0)
        & (cv_fde <= 800.0)
        & (gru_fde < cv_fde)
        & (gain >= 120.0)
    )
    if not np.any(valid):
        raise RuntimeError("No representative bend window passed geometric heading, speed-ratio, and FDE filters.")

    score = (
        -1.6 * np.abs(geom["heading_change_deg"] - 90.0)
        + 0.06 * geom["future_path_m"]
        + 0.18 * gain
        + 90.0 * np.clip(geom["curvature_ratio"] - 1.0, 0.0, 0.6)
        - 0.18 * np.abs(cv_fde - 545.0)
        - 0.04 * np.maximum(gain - 450.0, 0.0)
    )
    idx = int(np.flatnonzero(valid)[np.argmax(score[valid])])
    return BendSelection(
        idx=idx,
        turn_rate_deg_s=float(turn[idx]),
        cv_fde_m=float(cv_fde[idx]),
        gru_fde_m=float(gru_fde[idx]),
        heading_change_deg=float(geom["heading_change_deg"][idx]),
        step_ratio_min=float(geom["step_ratio_min"][idx]),
        step_ratio_max=float(geom["step_ratio_max"][idx]),
        future_path_m=float(geom["future_path_m"][idx]),
        future_chord_m=float(geom["future_chord_m"][idx]),
        curvature_ratio=float(geom["curvature_ratio"][idx]),
        score=float(score[idx]),
    )


def localize(*arrays: np.ndarray, origin: np.ndarray) -> list[np.ndarray]:
    return [np.asarray(a, dtype=float) - origin[None, :] for a in arrays]


def plot_bend_example(
    plt,
    out_dir: Path,
    anchors: dict[str, np.ndarray | int],
    cv_pred: np.ndarray,
    gru_pred: np.ndarray,
    selection: BendSelection,
    dpi: int,
) -> Path:
    i = selection.idx
    hist_xy = anchors["hist_xy_abs"]
    fut_xy = anchors["fut_xy"]
    mmsi = anchors["mmsi"]
    seg_id = anchors["seg_id"]
    anchor_offset = anchors["anchor_offset"]
    assert isinstance(hist_xy, np.ndarray)
    assert isinstance(fut_xy, np.ndarray)
    assert isinstance(mmsi, np.ndarray)
    assert isinstance(seg_id, np.ndarray)
    assert isinstance(anchor_offset, np.ndarray)

    origin = hist_xy[i, -1].astype(float)
    hist_l, truth_l, cv_l, gru_l = localize(
        hist_xy[i],
        fut_xy[i],
        cv_pred[i],
        gru_pred[i],
        origin=origin,
    )
    current = np.asarray([[0.0, 0.0]])

    fig, ax = plt.subplots(figsize=(9.4, 8.2), dpi=dpi)
    ax.plot(hist_l[:, 0], hist_l[:, 1], color="0.45", linewidth=3.0, marker="o", markersize=5.5, label="Input history")
    ax.scatter([0.0], [0.0], color="0.15", s=64, zorder=5)
    ax.plot(
        np.vstack([current, truth_l])[:, 0],
        np.vstack([current, truth_l])[:, 1],
        color="black",
        linestyle="-",
        marker="o",
        markersize=6,
        label="Truth",
    )
    ax.plot(
        np.vstack([current, cv_l])[:, 0],
        np.vstack([current, cv_l])[:, 1],
        color="tab:red",
        linestyle="--",
        marker="x",
        markersize=7,
        label="LSQ-CV",
    )
    ax.plot(
        np.vstack([current, gru_l])[:, 0],
        np.vstack([current, gru_l])[:, 1],
        color="tab:green",
        linestyle=":",
        marker="s",
        markersize=5.5,
        label="GRU",
    )

    ax.set_title("示例：弯道窗口（test，代表性）")
    ax.set_xlabel("Local x (m)")
    ax.set_ylabel("Local y (m)")
    ax.axis("equal")
    ax.legend(loc="best", frameon=True)
    ax.text(
        0.02,
        0.98,
        (
            f"CV FDE: {selection.cv_fde_m:.1f} m\n"
            f"GRU FDE: {selection.gru_fde_m:.1f} m\n"
            f"Heading change: {selection.heading_change_deg:.1f} deg\n"
            f"Step ratio: {selection.step_ratio_min:.2f}-{selection.step_ratio_max:.2f}"
        ),
        transform=ax.transAxes,
        va="top",
        ha="left",
        fontsize=13,
        bbox={"boxstyle": "round,pad=0.35", "facecolor": "white", "edgecolor": "0.75", "alpha": 0.92},
    )
    ax.text(
        0.98,
        0.02,
        f"window #{i} | MMSI {mmsi[i]} | seg {seg_id[i]} | offset {anchor_offset[i]}",
        transform=ax.transAxes,
        va="bottom",
        ha="right",
        fontsize=10.5,
        color="0.25",
    )
    fig.tight_layout()

    path = out_dir / "fig_bend_example.png"
    fig.savefig(path, dpi=dpi)
    plt.close(fig)
    return path


def maybe_plot_bend_gif(
    plt,
    out_dir: Path,
    anchors: dict[str, np.ndarray | int],
    cv_pred: np.ndarray,
    gru_pred: np.ndarray,
    selection: BendSelection,
    dpi: int,
) -> Path | None:
    try:
        from matplotlib.animation import FuncAnimation, PillowWriter
        import PIL  # noqa: F401
    except Exception:
        return None

    i = selection.idx
    hist_xy = anchors["hist_xy_abs"]
    fut_xy = anchors["fut_xy"]
    assert isinstance(hist_xy, np.ndarray)
    assert isinstance(fut_xy, np.ndarray)

    origin = hist_xy[i, -1].astype(float)
    hist_l, truth_l, cv_l, gru_l = localize(
        hist_xy[i],
        fut_xy[i],
        cv_pred[i],
        gru_pred[i],
        origin=origin,
    )
    current = np.asarray([[0.0, 0.0]])

    all_xy = np.vstack([hist_l, truth_l, cv_l, gru_l])
    pad = max(float(np.ptp(all_xy[:, 0])), float(np.ptp(all_xy[:, 1])), 1.0) * 0.16
    xmin, xmax = float(all_xy[:, 0].min() - pad), float(all_xy[:, 0].max() + pad)
    ymin, ymax = float(all_xy[:, 1].min() - pad), float(all_xy[:, 1].max() + pad)

    fig, ax = plt.subplots(figsize=(7.8, 7.0), dpi=dpi)

    def draw(frame: int) -> None:
        ax.clear()
        ax.plot(hist_l[:, 0], hist_l[:, 1], color="0.45", linewidth=2.8, marker="o", markersize=5.0, label="Input history")
        ax.scatter([0.0], [0.0], color="0.15", s=54, zorder=5)
        n = frame + 1
        for label, arr, color, style, marker in (
            ("Truth", truth_l, "black", "-", "o"),
            ("LSQ-CV", cv_l, "tab:red", "--", "x"),
            ("GRU", gru_l, "tab:green", ":", "s"),
        ):
            shown = np.vstack([current, arr[:n]])
            ax.plot(shown[:, 0], shown[:, 1], color=color, linestyle=style, marker=marker, markersize=5.5, label=label)
        ax.set_title("示例：弯道窗口（test，代表性）")
        ax.set_xlabel("Local x (m)")
        ax.set_ylabel("Local y (m)")
        ax.set_xlim(xmin, xmax)
        ax.set_ylim(ymin, ymax)
        ax.axis("equal")
        ax.grid(True, alpha=0.22)
        ax.legend(loc="best", frameon=True)

    anim = FuncAnimation(fig, draw, frames=H, interval=650, repeat=True)
    path = out_dir / "fig_bend_example.gif"
    anim.save(path, writer=PillowWriter(fps=1.5))
    plt.close(fig)
    return path


def select_encounter(
    anchors: dict[str, np.ndarray | int],
    cv_pred: np.ndarray,
    gru_pred: np.ndarray,
    data: dict[str, np.ndarray],
    grid_step_s: float,
    allow_fallback: bool,
) -> EncounterSelection:
    fut_xy = anchors["fut_xy"]
    fut_t = anchors["fut_t"]
    hist_xy = anchors["hist_xy_abs"]
    assert isinstance(fut_xy, np.ndarray)
    assert isinstance(fut_t, np.ndarray)
    assert isinstance(hist_xy, np.ndarray)

    true_danger = np.isin(data["true_risk"], list(DANGER_LEVELS))
    cv_safe = data["cv_risk_pred"] == "SAFE"
    gru_danger = np.isin(data["gru_risk_pred"], list(DANGER_LEVELS))
    exact = true_danger & cv_safe & gru_danger

    heading_angle = np.full(len(data["true_risk"]), np.nan, dtype=float)
    plot_span = np.full(len(data["true_risk"]), np.nan, dtype=float)
    for row in np.flatnonzero(exact):
        a = int(data["a_idx"][row])
        b = int(data["b_idx"][row])
        grid = _common_grid(fut_t[a], fut_t[b], grid_step_s)
        if grid is None:
            continue
        gt_a = _interp_traj(fut_t[a], fut_xy[a], grid)
        gt_b = _interp_traj(fut_t[b], fut_xy[b], grid)
        heading_angle[row] = _trajectory_heading_angle_deg(gt_a, gt_b)
        all_xy = np.vstack(
            [
                hist_xy[a],
                hist_xy[b],
                gt_a,
                gt_b,
                _interp_traj(fut_t[a], cv_pred[a], grid),
                _interp_traj(fut_t[b], cv_pred[b], grid),
                _interp_traj(fut_t[a], gru_pred[a], grid),
                _interp_traj(fut_t[b], gru_pred[b], grid),
            ]
        )
        plot_span[row] = float(max(np.ptp(all_xy[:, 0]), np.ptp(all_xy[:, 1])))

    clear_crossing = (
        exact
        & (heading_angle >= 35.0)
        & (plot_span <= 12_000.0)
        & (data["cv_dcpa_pred"] >= 1_000.0)
        & (data["cv_dcpa_pred"] <= 4_000.0)
        & (data["true_dcpa"] <= 150.0)
        & (data["gru_dcpa_pred"] <= 900.0)
    )
    broader_crossing = (
        exact
        & (heading_angle >= 35.0)
        & (plot_span <= 15_000.0)
        & (data["cv_dcpa_pred"] >= 1_000.0)
        & (data["cv_dcpa_pred"] <= 4_000.0)
        & (data["true_dcpa"] <= 500.0)
        & (data["gru_dcpa_pred"] <= 900.0)
    )
    moderate_cv_miss = exact & (data["cv_dcpa_pred"] > 0.5 * NM) & (data["cv_dcpa_pred"] <= 4_000.0)
    if np.any(clear_crossing):
        mask = clear_crossing
    elif np.any(broader_crossing):
        mask = broader_crossing
    elif np.any(moderate_cv_miss):
        mask = moderate_cv_miss
    else:
        mask = exact
    exact_match = True

    if not np.any(mask):
        if not allow_fallback:
            raise RuntimeError("No true-danger / CV-SAFE / GRU-danger encounter found.")
        mask = true_danger & data["cv_miss"]
        exact_match = False
    if not np.any(mask):
        raise RuntimeError("No CV miss encounter found in this sample.")

    cv_gap = np.maximum(data["cv_dcpa_pred"] - data["true_dcpa"], 0.0)
    gru_gap = np.abs(data["gru_dcpa_pred"] - data["true_dcpa"])
    score = (
        np.where(data["true_risk"] == "ALARM", 20_000.0, 10_000.0)
        + 6_000.0 * np.nan_to_num(heading_angle, nan=0.0)
        + np.clip(cv_gap, 0.0, 4_000.0)
        - 4.0 * np.clip(gru_gap, 0.0, 2_000.0)
        - 240.0 * np.clip(data["true_dcpa"], 0.0, 2_000.0)
        - 0.08 * np.nan_to_num(plot_span, nan=60_000.0)
    )
    row = int(np.flatnonzero(mask)[np.argmax(score[mask])])
    return EncounterSelection(
        row=row,
        a_idx=int(data["a_idx"][row]),
        b_idx=int(data["b_idx"][row]),
        exact_match=exact_match,
        heading_angle_deg=float(heading_angle[row]) if np.isfinite(heading_angle[row]) else float("nan"),
        plot_span_m=float(plot_span[row]) if np.isfinite(plot_span[row]) else float("nan"),
        score=float(score[row]),
    )


def plot_encounter_miss(
    plt,
    out_dir: Path,
    anchors: dict[str, np.ndarray | int],
    cv_pred: np.ndarray,
    gru_pred: np.ndarray,
    data: dict[str, np.ndarray],
    selection: EncounterSelection,
    grid_step_s: float,
    dpi: int,
) -> Path:
    from matplotlib.lines import Line2D
    from mpl_toolkits.axes_grid1.inset_locator import inset_axes, mark_inset

    a = selection.a_idx
    b = selection.b_idx
    row = selection.row

    fut_xy = anchors["fut_xy"]
    fut_t = anchors["fut_t"]
    hist_xy = anchors["hist_xy_abs"]
    t0 = anchors["t0"]
    mmsi = anchors["mmsi"]
    seg_id = anchors["seg_id"]
    assert isinstance(fut_xy, np.ndarray)
    assert isinstance(fut_t, np.ndarray)
    assert isinstance(hist_xy, np.ndarray)
    assert isinstance(t0, np.ndarray)
    assert isinstance(mmsi, np.ndarray)
    assert isinstance(seg_id, np.ndarray)

    grid = _common_grid(fut_t[a], fut_t[b], grid_step_s)
    if grid is None:
        raise RuntimeError("Selected encounter has no common future grid.")
    times_rel = grid - max(float(t0[a]), float(t0[b]))

    trajs = {
        "Truth": (
            _interp_traj(fut_t[a], fut_xy[a], grid),
            _interp_traj(fut_t[b], fut_xy[b], grid),
            "-",
            "black",
        ),
        "LSQ-CV": (
            _interp_traj(fut_t[a], cv_pred[a], grid),
            _interp_traj(fut_t[b], cv_pred[b], grid),
            "--",
            "tab:red",
        ),
        "GRU": (
            _interp_traj(fut_t[a], gru_pred[a], grid),
            _interp_traj(fut_t[b], gru_pred[b], grid),
            ":",
            "tab:green",
        ),
    }

    all_xy = np.vstack(
        [
            hist_xy[a],
            hist_xy[b],
            trajs["Truth"][0],
            trajs["Truth"][1],
            trajs["LSQ-CV"][0],
            trajs["LSQ-CV"][1],
            trajs["GRU"][0],
            trajs["GRU"][1],
        ]
    )
    origin = all_xy.mean(axis=0)

    ship_colors = {"A": "tab:blue", "B": "tab:orange"}
    fig, ax = plt.subplots(figsize=(10.6, 8.7), dpi=dpi)
    localized_trajs: dict[str, tuple[np.ndarray, np.ndarray, str, str]] = {}
    cpa_points: dict[str, tuple[np.ndarray, np.ndarray, float, float, str]] = {}

    for label, idx, color in (("A", a, ship_colors["A"]), ("B", b, ship_colors["B"])):
        hist_l = hist_xy[idx] - origin[None, :]
        ax.plot(hist_l[:, 0], hist_l[:, 1], color=color, alpha=0.30, linewidth=3.0, marker="o", markersize=4.5)
        ax.scatter(hist_l[-1, 0], hist_l[-1, 1], color=color, s=72, marker="o", edgecolor="white", linewidth=1.1, zorder=5)
        ax.text(hist_l[-1, 0], hist_l[-1, 1], f" {label} now", color=color, fontsize=11, fontweight=600)

    metric_lines: list[str] = []
    for model_name, (traj_a, traj_b, style, metric_color) in trajs.items():
        dcpa, tcpa = cpa_tcpa_numerical(traj_a, traj_b, times_rel)
        risk = risk_level(dcpa, tcpa)
        metric_lines.append(f"{model_name}: {risk}, DCPA {dcpa:.0f} m, TCPA {tcpa:.0f} s")

        pa, pb = _closest_points(traj_a, traj_b, times_rel, tcpa)
        cpa_points[model_name] = (pa, pb, dcpa, tcpa, risk)
        pa_l = pa - origin
        pb_l = pb - origin
        ax.plot([pa_l[0], pb_l[0]], [pa_l[1], pb_l[1]], color=metric_color, linewidth=2.0, alpha=0.62)

        localized_trajs[model_name] = (
            traj_a - origin[None, :],
            traj_b - origin[None, :],
            style,
            metric_color,
        )
        for label, traj, color in (("A", traj_a, ship_colors["A"]), ("B", traj_b, ship_colors["B"])):
            traj_l = traj - origin[None, :]
            ax.plot(
                traj_l[:, 0],
                traj_l[:, 1],
                linestyle=style,
                color=color,
                linewidth=2.8,
                marker="o",
                markersize=4.5,
                alpha=0.96,
            )

    status = "exact case" if selection.exact_match else "fallback smoke case"
    ax.set_title("Real encounter example: CV miss, GRU catches danger (test)")
    ax.set_xlabel("Local x (m)")
    ax.set_ylabel("Local y (m)")
    ax.axis("equal")

    truth_pa, truth_pb, truth_dcpa, _, _ = cpa_points["Truth"]
    inset_center = ((truth_pa + truth_pb) / 2.0) - origin
    inset_radius = max(180.0, min(450.0, 4.0 * truth_dcpa))
    axins = inset_axes(ax, width="35%", height="35%", loc="upper right", borderpad=1.1)
    for model_name, (traj_a_l, traj_b_l, style, _) in localized_trajs.items():
        alpha = 1.0 if model_name in {"Truth", "GRU"} else 0.35
        zorder = 4 if model_name == "Truth" else 3 if model_name == "GRU" else 2
        axins.plot(traj_a_l[:, 0], traj_a_l[:, 1], linestyle=style, color=ship_colors["A"], linewidth=2.2, alpha=alpha, zorder=zorder)
        axins.plot(traj_b_l[:, 0], traj_b_l[:, 1], linestyle=style, color=ship_colors["B"], linewidth=2.2, alpha=alpha, zorder=zorder)

    for model_name, color in (("Truth", "black"), ("GRU", "tab:green"), ("LSQ-CV", "tab:red")):
        pa, pb, dcpa, _, _ = cpa_points[model_name]
        pa_l = pa - origin
        pb_l = pb - origin
        axins.plot([pa_l[0], pb_l[0]], [pa_l[1], pb_l[1]], color=color, linewidth=1.8, alpha=0.78)
        if model_name == "Truth":
            axins.scatter([pa_l[0], pb_l[0]], [pa_l[1], pb_l[1]], color="black", s=22, zorder=6)
            axins.text(
                inset_center[0],
                inset_center[1] + inset_radius * 0.72,
                f"Truth DCPA {dcpa:.0f} m",
                ha="center",
                va="bottom",
                fontsize=9,
                bbox={"boxstyle": "round,pad=0.2", "facecolor": "white", "edgecolor": "0.8", "alpha": 0.9},
            )

    axins.set_title("CPA inset", fontsize=10)
    axins.set_xlim(inset_center[0] - inset_radius, inset_center[0] + inset_radius)
    axins.set_ylim(inset_center[1] - inset_radius, inset_center[1] + inset_radius)
    axins.set_aspect("equal", adjustable="box")
    axins.grid(True, alpha=0.25)
    axins.tick_params(labelsize=8)
    mark_inset(ax, axins, loc1=2, loc2=4, fc="none", ec="0.55", linewidth=1.0, alpha=0.7)

    vessel_legend = ax.legend(
        handles=[
            Line2D([0], [0], color=ship_colors["A"], linewidth=3, label=f"Ship A MMSI {mmsi[a]}"),
            Line2D([0], [0], color=ship_colors["B"], linewidth=3, label=f"Ship B MMSI {mmsi[b]}"),
        ],
        loc="lower left",
        frameon=True,
    )
    ax.add_artist(vessel_legend)
    ax.legend(
        handles=[
            Line2D([0], [0], color="0.20", linestyle="-", linewidth=2.8, label="Truth future"),
            Line2D([0], [0], color="0.20", linestyle="--", linewidth=2.8, label="LSQ-CV future"),
            Line2D([0], [0], color="0.20", linestyle=":", linewidth=2.8, label="GRU future"),
        ],
        loc="lower right",
        frameon=True,
    )
    ax.text(
        0.02,
        0.98,
        "\n".join(metric_lines)
        + f"\nHeading angle: {selection.heading_angle_deg:.1f} deg"
        + f"\nrow {row} | {status} | segs {seg_id[a]} / {seg_id[b]}",
        transform=ax.transAxes,
        va="top",
        ha="left",
        fontsize=12.4,
        bbox={"boxstyle": "round,pad=0.38", "facecolor": "white", "edgecolor": "0.75", "alpha": 0.93},
    )
    fig.subplots_adjust(left=0.08, right=0.98, top=0.91, bottom=0.09)

    path = out_dir / "fig_encounter_miss.png"
    fig.savefig(path, dpi=dpi)
    plt.close(fig)
    return path


def _annotate_reduction(ax, x: float, y: float, cv: float, gru: float, suffix: str = "%") -> None:
    reduction = (cv - gru) / cv * 100.0
    ax.text(x, y, f"-{reduction:.1f}{suffix}", ha="center", va="bottom", fontsize=12.5, fontweight=600)


def plot_metrics(plt, out_dir: Path, dpi: int) -> Path:
    fig, axes = plt.subplots(1, 3, figsize=(18.0, 5.8), dpi=dpi)

    cv_h = np.asarray([109.1, 202.7, 313.8])
    gru_h = np.asarray([98.1, 161.4, 224.9])
    x = np.arange(len(HORIZON_LABELS))
    axes[0].plot(x, cv_h, color="tab:red", marker="o", markersize=7, label="LSQ-CV")
    axes[0].plot(x, gru_h, color="tab:green", marker="s", markersize=6.5, label="GRU")
    axes[0].set_xticks(x, HORIZON_LABELS)
    axes[0].set_ylabel("FDE (m)")
    axes[0].set_title("(a) FDE by horizon")
    axes[0].legend(frameon=True)
    axes[0].set_ylim(0, max(cv_h) * 1.25)
    for xi, c, g in zip(x, cv_h, gru_h):
        axes[0].text(xi, c + 8, f"{c:.1f}", color="tab:red", ha="center", fontsize=11)
        axes[0].text(xi, g - 20, f"{g:.1f}", color="tab:green", ha="center", fontsize=11)
        _annotate_reduction(axes[0], float(xi), max(c, g) + 30, float(c), float(g))

    labels = ["Straight", "Bend"]
    cv_b = np.asarray([199.3, 544.8])
    gru_b = np.asarray([151.3, 363.9])
    x2 = np.arange(len(labels))
    width = 0.34
    axes[1].bar(x2 - width / 2, cv_b, width, color="tab:red", label="LSQ-CV")
    axes[1].bar(x2 + width / 2, gru_b, width, color="tab:green", label="GRU")
    axes[1].set_xticks(x2, labels)
    axes[1].set_ylabel("FDE (m)")
    axes[1].set_title("(b) Straight vs bend FDE")
    axes[1].set_ylim(0, max(cv_b) * 1.25)
    axes[1].legend(frameon=True)
    for xi, c, g in zip(x2, cv_b, gru_b):
        axes[1].text(xi - width / 2, c + 10, f"{c:.1f}", ha="center", fontsize=11)
        axes[1].text(xi + width / 2, g + 10, f"{g:.1f}", ha="center", fontsize=11)
        _annotate_reduction(axes[1], float(xi), max(c, g) + 52, float(c), float(g))

    miss_labels = ["Global", "Bend"]
    cv_m = np.asarray([12.2, 16.5])
    gru_m = np.asarray([8.6, 11.4])
    x3 = np.arange(len(miss_labels))
    axes[2].bar(x3 - width / 2, cv_m, width, color="tab:red", label="LSQ-CV")
    axes[2].bar(x3 + width / 2, gru_m, width, color="tab:green", label="GRU")
    axes[2].set_xticks(x3, miss_labels)
    axes[2].set_ylabel("Miss rate among true danger (%)")
    axes[2].set_title("(c) True-danger miss rate")
    axes[2].set_ylim(0, max(cv_m) * 1.38)
    axes[2].legend(frameon=True)
    for xi, c, g in zip(x3, cv_m, gru_m):
        axes[2].text(xi - width / 2, c + 0.35, f"{c:.1f}%", ha="center", fontsize=11)
        axes[2].text(xi + width / 2, g + 0.35, f"{g:.1f}%", ha="center", fontsize=11)
        _annotate_reduction(axes[2], float(xi), max(c, g) + 1.6, float(c), float(g))

    fig.suptitle("Trajectory prediction and downstream risk metrics", y=1.02)
    fig.tight_layout()

    path = out_dir / "fig_metrics.png"
    fig.savefig(path, dpi=dpi, bbox_inches="tight")
    plt.close(fig)
    return path


def write_selection_report(path: Path, payload: dict[str, Any]) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def main() -> None:
    args = parse_args()
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    plt = setup_matplotlib()

    dataset = Path(args.dataset)
    windows_test = Path(args.windows_test)
    model_path = Path(args.model)

    print(f"[figures] loading test anchors from {dataset}")
    anchors = build_anchors(dataset)
    anchor_count = len(anchors["mmsi"])
    print(f"[figures] anchors={anchor_count:,} test_segments={anchors['segments_with_anchors']:,}")

    feature_check = check_feature_alignment(anchors, windows_test, args.feature_check_samples)
    print(f"[figures] feature_check={feature_check}")
    if feature_check["status"] != "passed":
        raise RuntimeError(f"Feature alignment failed: {feature_check}")

    print("[figures] predicting LSQ-CV via geometry.cv_predict(window=3)")
    cv_pred = predict_cv(anchors)
    print(f"[figures] predicting GRU from {model_path}")
    gru_pred = predict_gru(anchors, model_path, args.hidden, args.layers, args.batch_size, args.device)

    bend = select_bend_window(anchors, cv_pred, gru_pred)
    bend_path = plot_bend_example(plt, out_dir, anchors, cv_pred, gru_pred, bend, args.dpi)
    gif_path = None if args.no_gif else maybe_plot_bend_gif(plt, out_dir, anchors, cv_pred, gru_pred, bend, args.dpi)
    print(
        "[figures] fig_bend_example.png "
        f"window={bend.idx} turn={bend.turn_rate_deg_s:.6f} "
        f"cv_fde={bend.cv_fde_m:.1f}m gru_fde={bend.gru_fde_m:.1f}m "
        f"heading_change={bend.heading_change_deg:.1f}deg "
        f"step_ratio={bend.step_ratio_min:.2f}-{bend.step_ratio_max:.2f}"
    )
    if gif_path is not None:
        print(f"[figures] fig_bend_example.gif wrote {gif_path}")
    else:
        print("[figures] fig_bend_example.gif skipped")

    print(
        f"[figures] mining encounters dt<{args.time_window_s:g}s "
        f"distance<{args.radius_m:g}m max={args.max_encounters}"
    )
    a_idx, b_idx = mine_pairs(anchors, args.time_window_s, args.radius_m, args.max_encounters)
    print(f"[figures] mined_pairs={len(a_idx):,}")
    turns = anchors["turn"]
    assert isinstance(turns, np.ndarray)
    turn_p75 = float(np.nanpercentile(turns, 75))
    data, skipped = evaluate_pairs(anchors, cv_pred, gru_pred, a_idx, b_idx, args.grid_step_s, turn_p75)
    if len(data.get("true_risk", [])) == 0:
        raise RuntimeError("No evaluable encounters after common-grid filtering.")
    print(f"[figures] evaluated_encounters={len(data['true_risk']):,} skipped_no_overlap={skipped:,}")

    encounter = select_encounter(anchors, cv_pred, gru_pred, data, args.grid_step_s, args.allow_fallback_encounter)
    encounter_path = plot_encounter_miss(
        plt,
        out_dir,
        anchors,
        cv_pred,
        gru_pred,
        data,
        encounter,
        args.grid_step_s,
        args.dpi,
    )
    print(
        "[figures] fig_encounter_miss.png "
        f"row={encounter.row} a_idx={encounter.a_idx} b_idx={encounter.b_idx} "
        f"true={data['true_risk'][encounter.row]} "
        f"cv={data['cv_risk_pred'][encounter.row]} "
        f"gru={data['gru_risk_pred'][encounter.row]} "
        f"true_dcpa={data['true_dcpa'][encounter.row]:.1f}m "
        f"cv_dcpa={data['cv_dcpa_pred'][encounter.row]:.1f}m "
        f"gru_dcpa={data['gru_dcpa_pred'][encounter.row]:.1f}m "
        f"heading_angle={encounter.heading_angle_deg:.1f}deg "
        f"exact={encounter.exact_match}"
    )

    metrics_path = out_dir / "fig_metrics.png"
    if args.with_metrics:
        metrics_path = plot_metrics(plt, out_dir, args.dpi)
        print("[figures] fig_metrics.png uses fixed verified values from task prompt")
    else:
        print("[figures] fig_metrics.png skipped; existing accepted file left untouched")

    report = {
        "inputs": {
            "dataset": str(dataset),
            "windows_test": str(windows_test),
            "model": str(model_path),
        },
        "feature_alignment_check": feature_check,
        "cv": {"source": "inland_traj.geometry.cv_predict", "window": 3},
        "gru": {
            "architecture": "train_gru.GRUPredictor(in_dim=6, hidden=128, layers=2)",
            "restore": "pred_km * 1000 + last absolute xy",
        },
        "bend_window": {
            "window_index": bend.idx,
            "turn_rate_deg_s": bend.turn_rate_deg_s,
            "heading_change_deg": bend.heading_change_deg,
            "step_ratio_min": bend.step_ratio_min,
            "step_ratio_max": bend.step_ratio_max,
            "future_path_m": bend.future_path_m,
            "future_chord_m": bend.future_chord_m,
            "curvature_ratio": bend.curvature_ratio,
            "cv_fde_m": bend.cv_fde_m,
            "gru_fde_m": bend.gru_fde_m,
            "score": bend.score,
            "filters": {
                "heading_change_deg_min": 25.0,
                "step_ratio_range": [0.6, 1.4],
                "cv_fde_m_range": [400.0, 800.0],
                "requires_gru_fde_lt_cv": True,
            },
        },
        "encounter": {
            "row": encounter.row,
            "a_idx": encounter.a_idx,
            "b_idx": encounter.b_idx,
            "heading_angle_deg": encounter.heading_angle_deg,
            "plot_span_m": encounter.plot_span_m,
            "true_risk": str(data["true_risk"][encounter.row]),
            "cv_risk": str(data["cv_risk_pred"][encounter.row]),
            "gru_risk": str(data["gru_risk_pred"][encounter.row]),
            "true_dcpa_m": float(data["true_dcpa"][encounter.row]),
            "true_tcpa_s": float(data["true_tcpa"][encounter.row]),
            "cv_dcpa_m": float(data["cv_dcpa_pred"][encounter.row]),
            "cv_tcpa_s": float(data["cv_tcpa_pred"][encounter.row]),
            "gru_dcpa_m": float(data["gru_dcpa_pred"][encounter.row]),
            "gru_tcpa_s": float(data["gru_tcpa_pred"][encounter.row]),
            "exact_cv_safe_gru_danger_case": encounter.exact_match,
            "score": encounter.score,
            "filters": {
                "requires_true_danger": ["ALARM", "WARNING"],
                "requires_cv_risk": "SAFE",
                "requires_gru_danger": ["ALARM", "WARNING"],
                "preferred_heading_angle_deg_min": 35.0,
            },
        },
        "outputs": {
            "bend_png": str(bend_path),
            "bend_gif": None if gif_path is None else str(gif_path),
            "encounter_png": str(encounter_path),
            "metrics_png": str(metrics_path),
        },
    }
    report_path = out_dir / "selection_report.json"
    write_selection_report(report_path, report)
    print(f"[figures] wrote {report_path}")


if __name__ == "__main__":
    main()
