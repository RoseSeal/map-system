#!/usr/bin/env python3
"""step-5 窗口化 + CV 基线 — 从 step-2 轨迹段构造监督样本并评 CV 基线.

窗口：每段内连续 K=10 个真实观测点为输入，其后第 1/2/3 个观测点位置为目标
（≈ +2.5/+5/+7.5min @ ~153s 节奏，不插值、不跨段）。沿用 step-2 的 by-vessel split。

CV 基线：用输入窗口末 W_CV=3 点的**最小二乘恒速度**（对齐 geometry.cv_predict 语义，
而非更弱的末两点速度，避免给 learned 立稻草人对手）外推到三个目标时刻，算 ADE/FDE（米）。

用法:
    python scripts/build_windows.py --dataset runtime/dataset/segments.parquet
        --out runtime/windows [--k 10 --stride 1]
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

H = 3      # 预测未来第 1/2/3 个观测点
W_CV = 3   # CV 速度用末 W_CV 点最小二乘拟合，对齐 geometry.cv_predict(window=3)


def _lsq_velocity(xw, yw, tw, w):
    """末 w 点最小二乘速度 (m/s)。输入 (W,k) 各取末 w 列；slope 与 polyfit 一致（对中心化不变）。"""
    t = tw[:, -w:] - tw[:, -w:].mean(1, keepdims=True)
    x = xw[:, -w:] - xw[:, -w:].mean(1, keepdims=True)
    y = yw[:, -w:] - yw[:, -w:].mean(1, keepdims=True)
    denom = np.maximum((t * t).sum(1), 1e-6)
    return np.stack([(t * x).sum(1) / denom, (t * y).sum(1) / denom], axis=-1)


def _safe_nanmean(a):
    """逐行 nanmean，全 NaN 行返回 NaN 且不触发 numpy 空切片警告。"""
    cnt = np.sum(~np.isnan(a), axis=1)
    return np.where(cnt > 0, np.nansum(a, axis=1) / np.maximum(cnt, 1), np.nan)


def build_segment_windows(seg: pd.DataFrame, k: int, stride: int):
    """单段 → 窗口数组。返回 dict 或 None（点数不足）。"""
    n = len(seg)
    if n < k + H:
        return None
    x = seg["x"].to_numpy(); y = seg["y"].to_numpy(); t = seg["t"].to_numpy().astype(float)
    sog = seg["sog"].to_numpy(); cog = seg["cog"].to_numpy(); turn = seg["turn_rate_deg_s"].to_numpy()
    starts = np.arange(0, n - k - H + 1, stride)
    in_idx = starts[:, None] + np.arange(k)        # (W,k)
    tgt_idx = starts[:, None] + (k + np.arange(H))  # (W,H)
    last = in_idx[:, -1]                            # 窗口末点（原点）
    lx, ly, lt = x[last], y[last], t[last]
    return {
        "in_xy": np.stack([x[in_idx] - lx[:, None], y[in_idx] - ly[:, None]], axis=-1),  # (W,k,2) 相对末点
        "in_dt": np.diff(t[in_idx], axis=1, prepend=t[in_idx][:, :1]),                    # (W,k)
        "in_sog": np.nan_to_num(sog[in_idx], nan=0.0),
        "in_cog": np.nan_to_num(cog[in_idx], nan=0.0),
        "tgt_xy": np.stack([x[tgt_idx] - lx[:, None], y[tgt_idx] - ly[:, None]], axis=-1),  # (W,H,2)
        "tgt_dt": t[tgt_idx] - lt[:, None],                                                  # (W,H) 距末点秒
        "in_xy_abs_last": np.stack([lx, ly], axis=-1),    # (W,2) 供需要绝对坐标时用
        "in_vel": _lsq_velocity(x[in_idx], y[in_idx], t[in_idx], W_CV),  # LSQ 恒速度，对齐 geometry.cv_predict
        "turn": _safe_nanmean(turn[tgt_idx]),              # (W,) 目标段平均转向率，供 step-6 分桶
    }


def cv_ade_fde(W: dict):
    """向量化 CV：末点恒速度外推到目标时刻。返回每 horizon 的位移误差 (N,H)。"""
    vel = W["in_vel"]                       # (N,2)
    cv_pred = vel[:, None, :] * W["tgt_dt"][:, :, None]   # (N,H,2) 相对末点的预测位移
    err = np.linalg.norm(cv_pred - W["tgt_xy"], axis=2)   # (N,H)
    return err


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dataset", default="runtime/dataset/segments.parquet")
    ap.add_argument("--out", default="runtime/windows")
    ap.add_argument("--k", type=int, default=10)
    ap.add_argument("--stride", type=int, default=1)
    args = ap.parse_args()

    out = Path(args.out); out.mkdir(parents=True, exist_ok=True)
    df = pd.read_parquet(args.dataset)

    per_split: dict = {s: [] for s in ("train", "val", "test")}
    for _, seg in df.groupby("seg_id", sort=False):
        w = build_segment_windows(seg, args.k, args.stride)
        if w is not None:
            per_split[seg["split"].iloc[0]].append(w)

    report = {"k": args.k, "stride": args.stride, "horizons_steps": [1, 2, 3]}
    for split, chunks in per_split.items():
        if not chunks:
            continue
        W = {key: np.concatenate([c[key] for c in chunks], axis=0) for key in chunks[0]}
        np.savez_compressed(out / f"{split}.npz", **{k: v.astype(np.float32) for k, v in W.items()})
        err = cv_ade_fde(W)  # (N,H)
        report[split] = {
            "windows": int(len(err)),
            "cv_ade_per_horizon_m": [round(float(err[:, h].mean()), 1) for h in range(H)],
            "cv_ade_m": round(float(err.mean()), 1),
            "cv_fde_m": round(float(err[:, -1].mean()), 1),
            "tgt_dt_median_s": [round(float(np.median(W["tgt_dt"][:, h])), 0) for h in range(H)],
        }
        print(f"[windows] {split}: {len(err):,} 窗口 | "
              f"CV ADE/horizon(m)={report[split]['cv_ade_per_horizon_m']} | "
              f"CV FDE(m)={report[split]['cv_fde_m']}")

    (out / "windows_manifest.json").write_text(json.dumps(report, ensure_ascii=False, indent=2),
                                               encoding="utf-8")
    print(f"[windows] 保存: {out}/*.npz  manifest: {out}/windows_manifest.json")


if __name__ == "__main__":
    main()
