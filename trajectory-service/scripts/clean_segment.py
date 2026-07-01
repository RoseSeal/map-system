#!/usr/bin/env python3
"""step-2 清洗与轨迹切分 — NBSDC 内河 AIS.

对照 docs/v1/step-2.md：行过滤 → 去噪去重 → 段级静止剔除 → gap 切航程段 →
弯道标注 → 按船划分 train/val/test。**保留真实观测点，不做目标插值**。

用法:
    python scripts/clean_segment.py --csv <ais.csv> --out runtime/dataset
    [--chunksize 1000000]

设计要点:
  - stage1 分块过滤（合法 9 位 MMSI + 武汉框 + 可解析时间）→ 落 parquet，省内存。
  - stage2 读 parquet，向量化按船计算：去重、局部 xy、位移速度；丢 ≤0.5kn 近静止点
    （弯道速度远高于此，不会误删），再按存活点的时间 gap 切航程段——锚泊被丢后自然
    形成大 gap 被切开，而过弯短暂减速点（>0.5kn）保留。
  - 弯道标注用**轨迹方位角变化率**（位置几何，比被哨兵污染的 COG 稳）。
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
import pyarrow as pa  # noqa: E402
import pyarrow.parquet as pq  # noqa: E402

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from inland_traj.geometry import KNOT, to_local_xy  # noqa: E402

WUHAN_BBOX = {"lon_min": 113.5, "lon_max": 115.1, "lat_min": 29.8, "lat_max": 31.0}
LON0, LAT0 = 114.3, 30.5  # 局部投影参考点（武汉框中心附近）
COLS = ["MMSI", "MSGTIME", "LON", "LAT", "SpeedOverGround", "CourseOverGround"]

# 清洗参数（默认值，可经抽样校验调整）
MOVE_THRESH_KN = 0.5      # 低速阈值（节）：≤ 视为低速点（仅用于识别持续静止段）
STAT_RUN_S = 300          # 持续低速 > 此秒数判为静止段并整段剔除（短暂减速保留）
GAP_MAX_S = 600           # 航程段时间 gap 上限（秒）：存活点间隔超此即断段
MIN_POINTS = 20           # 段最小点数
MIN_SPAN_M = 300          # 段最小空间跨度（米）
BEND_TURN_DEG_S = 0.10    # 弯道判据：平滑方位角变化率阈值（度/秒）≈ 6°/min
MIN_DT_S = 15             # 弯道可靠步最小 dt（秒）：更短的步被 GPS 抖动主导
MIN_DIST_M = 15           # 弯道可靠步最小位移（米）：抖动会把小位移放大成假转向
MAX_TURN_DEG_S = 3.0      # 物理转向率上限（度/秒）：超出视为传感器噪声
THIN_SPACING_S = 30       # 突发去冗：段内按此时间分箱、每箱留首点（下采样真实点，非插值）


def stage1_filter(csv_path: Path, parquet_path: Path, chunksize: int) -> dict:
    """分块过滤合法行，落 parquet。返回统计。"""
    writer = None
    kept = total = 0
    schema = pa.schema([
        ("mmsi", pa.int64()), ("t", pa.int64()),
        ("lon", pa.float64()), ("lat", pa.float64()),
        ("sog", pa.float64()), ("cog", pa.float64()),
    ])
    reader = pd.read_csv(csv_path, usecols=COLS, chunksize=chunksize,
                         dtype={"MMSI": "string"})
    for chunk in reader:
        total += len(chunk)
        mmsi = chunk["MMSI"].str.strip()
        legal = mmsi.str.fullmatch(r"\d{9}", na=False)
        lon = pd.to_numeric(chunk["LON"], errors="coerce")
        lat = pd.to_numeric(chunk["LAT"], errors="coerce")
        in_box = (lon.between(WUHAN_BBOX["lon_min"], WUHAN_BBOX["lon_max"])
                  & lat.between(WUHAN_BBOX["lat_min"], WUHAN_BBOX["lat_max"]))
        t = pd.to_datetime(chunk["MSGTIME"], errors="coerce")
        keep = legal & in_box & t.notna()
        if not keep.any():
            continue
        sub = chunk[keep]
        sog = pd.to_numeric(sub["SpeedOverGround"], errors="coerce")
        cog = pd.to_numeric(sub["CourseOverGround"], errors="coerce")
        sog = sog.where(sog <= 100)   # >100 视为哨兵 → NaN
        cog = cog.where(cog < 360)    # ≥360 视为哨兵 → NaN
        out = pd.DataFrame({
            "mmsi": mmsi[keep].astype("int64").to_numpy(),
            "t": t[keep].to_numpy().astype("datetime64[s]").astype("int64"),  # epoch 秒（不依赖 pandas 时间分辨率）
            "lon": lon[keep].to_numpy(), "lat": lat[keep].to_numpy(),
            "sog": sog.to_numpy(), "cog": cog.to_numpy(),
        })
        table = pa.Table.from_pandas(out, schema=schema, preserve_index=False)
        if writer is None:
            writer = pq.ParquetWriter(parquet_path, schema)
        writer.write_table(table)
        kept += len(out)
    if writer is not None:
        writer.close()
    return {"total_rows": total, "filtered_rows": kept}


def _ang_diff(deg: np.ndarray) -> np.ndarray:
    """相邻方位角的最小有符号差（度，[-180,180]）。"""
    return (np.diff(deg, prepend=deg[:1]) + 180) % 360 - 180


def stage2_segment(parquet_path: Path, out: Path) -> dict:
    """读 parquet，向量化清洗 + 切段 + 弯道标注 + 划分。返回统计并落盘。"""
    df = pd.read_parquet(parquet_path)
    df = df.drop_duplicates(["mmsi", "t"]).sort_values(["mmsi", "t"]).reset_index(drop=True)

    x, y = to_local_xy(df["lon"].to_numpy(), df["lat"].to_numpy(), LON0, LAT0)
    df["x"], df["y"] = x, y

    # 按船相邻位移与有效速度（节）：优先 SOG，缺失用位置位移速度
    mmsi = df["mmsi"].to_numpy()
    vstart = np.concatenate([[True], mmsi[1:] != mmsi[:-1]])
    t = df["t"].to_numpy()
    dt = np.where(vstart, np.inf, np.diff(t, prepend=t[:1]).astype(float))
    dist = np.hypot(np.diff(x, prepend=x[:1]), np.diff(y, prepend=y[:1]))
    speed_pos_kn = np.where((~vstart) & (dt > 0), (dist / dt) / KNOT, np.nan)
    eff_kn = df["sog"].to_numpy().astype(float).copy()
    eff_kn = np.where(np.isnan(eff_kn), speed_pos_kn, eff_kn)

    # 段级静止剔除：标出持续 > STAT_RUN_S 的低速运行整段并剔除（短暂减速点保留），
    # 并在被剔除停顿处断段——不按单点速度删点（见 step-2 契约）。
    slow = eff_kn <= MOVE_THRESH_KN  # NaN 比较为 False → 无速度点视为非静止、保留
    block_change = vstart | np.concatenate([[True], slow[1:] != slow[:-1]])
    run_id = np.cumsum(block_change)
    ts = pd.Series(t)
    run_span = (ts.groupby(run_id).transform("max") - ts.groupby(run_id).transform("min")).to_numpy()
    drop = slow & (run_span > STAT_RUN_S)
    prev_drop = np.concatenate([[False], drop[:-1]])
    new_seg = vstart | (dt > GAP_MAX_S) | prev_drop  # 停顿处（prev_drop）断段
    df = df.assign(seg_id=np.cumsum(new_seg) - 1)[~drop].reset_index(drop=True)

    # 突发去冗：段内按时间分箱（THIN_SPACING_S），每箱留首点——抹平 1s 突发、规整采样
    seg_start = df.groupby("seg_id")["t"].transform("min")
    df["_bin"] = (df["t"] - seg_start) // THIN_SPACING_S
    mv = (df.drop_duplicates(["seg_id", "_bin"], keep="first")
            .drop(columns="_bin").reset_index(drop=True))

    # 段内 dt
    seg = mv["seg_id"].to_numpy()
    seg_prev = np.concatenate([[False], seg[1:] == seg[:-1]])
    dt2 = np.diff(mv["t"].to_numpy(), prepend=mv["t"].to_numpy()[:1]).astype(float)
    mv["dt"] = np.where(seg_prev, dt2, np.nan)

    # 弯道信号：段内方位角变化率（可靠步 dt≥15s 且位移≥15m）+ 物理上限裁噪 + **段内**平滑。
    dxb = np.diff(mv["x"].to_numpy(), prepend=mv["x"].to_numpy()[:1])
    dyb = np.diff(mv["y"].to_numpy(), prepend=mv["y"].to_numpy()[:1])
    bearing = np.degrees(np.arctan2(dyb, dxb))
    step_dist = np.hypot(dxb, dyb)
    reliable = seg_prev & (mv["dt"].to_numpy() >= MIN_DT_S) & (step_dist >= MIN_DIST_M)
    turn_rate = np.where(reliable, np.abs(_ang_diff(bearing)) / mv["dt"].to_numpy(), np.nan)
    turn_rate = np.where(turn_rate <= MAX_TURN_DEG_S, turn_rate, np.nan)  # 物理上限外视为噪声
    mv["turn_rate_deg_s"] = turn_rate
    mv["turn_rate_smooth"] = mv.groupby("seg_id")["turn_rate_deg_s"].transform(
        lambda c: c.rolling(5, center=True, min_periods=1).median())
    mv["is_bend"] = mv["turn_rate_smooth"] > BEND_TURN_DEG_S

    # 段级过滤：点数 + 空间跨度
    g = mv.groupby("seg_id")
    span = (g["x"].agg(lambda c: c.max() - c.min()) ** 2
            + g["y"].agg(lambda c: c.max() - c.min()) ** 2) ** 0.5
    npts = g.size()
    good = set(span[(npts >= MIN_POINTS) & (span >= MIN_SPAN_M)].index)
    mv = mv[mv["seg_id"].isin(good)].copy()

    # 段号重排为连续；按船划分（mmsi % 10：0-7 train, 8 val, 9 test）
    mv["seg_id"] = mv.groupby("seg_id").ngroup()
    bucket = mv["mmsi"] % 10
    mv["split"] = np.where(bucket <= 7, "train", np.where(bucket == 8, "val", "test"))

    out.mkdir(parents=True, exist_ok=True)
    keep_cols = ["mmsi", "seg_id", "t", "dt", "lon", "lat", "x", "y",
                 "sog", "cog", "turn_rate_deg_s", "is_bend", "split"]
    mv[keep_cols].to_parquet(out / "segments.parquet", index=False)
    _sample_plots(mv, out / "plots")

    seg_sizes = mv.groupby("seg_id").size()
    stats = {
        "moving_points": int(len(mv)),
        "segments": int(mv["seg_id"].nunique()),
        "vessels": int(mv["mmsi"].nunique()),
        "bend_point_fraction": round(float(mv["is_bend"].mean()), 4),
        "seg_points": {
            "min": int(seg_sizes.min()), "median": float(seg_sizes.median()),
            "p90": float(seg_sizes.quantile(0.9)), "max": int(seg_sizes.max()),
        },
        "split_segments": {
            k: int(mv[mv["split"] == k]["seg_id"].nunique()) for k in ("train", "val", "test")
        },
        "turn_rate_deg_s_quantiles": {
            q: round(float(mv["turn_rate_deg_s"].quantile(v)), 4)
            for q, v in {"p50": .5, "p90": .9, "p99": .99}.items()
        },
    }
    return stats


def _sample_plots(mv: pd.DataFrame, plots: Path) -> None:
    plots.mkdir(parents=True, exist_ok=True)
    for stale in plots.glob("seg_*.png"):  # 清理上轮残留，避免新旧段图混存
        stale.unlink()
    sizes = mv.groupby("seg_id").size().sort_values(ascending=False)
    for sid in sizes.head(6).index:
        seg = mv[mv["seg_id"] == sid]
        plt.figure(figsize=(5, 5))
        plt.scatter(seg["x"], seg["y"], c=seg["is_bend"].astype(int),
                    cmap="coolwarm", s=5)
        plt.title(f"seg {sid} mmsi {seg['mmsi'].iloc[0]} (n={len(seg)}, bend=red)")
        plt.xlabel("x(m)"); plt.ylabel("y(m)"); plt.axis("equal")
        plt.tight_layout()
        plt.savefig(plots / f"seg_{sid}.png", dpi=110)
        plt.close()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", required=True)
    ap.add_argument("--out", default="runtime/dataset")
    ap.add_argument("--chunksize", type=int, default=1_000_000)
    args = ap.parse_args()

    csv_path, out = Path(args.csv), Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    parquet_path = out / "filtered.parquet"

    print("[clean] stage1 过滤中…")
    s1 = stage1_filter(csv_path, parquet_path, args.chunksize)
    print(f"[clean] stage1: {s1['filtered_rows']:,} / {s1['total_rows']:,} 行通过过滤")

    print("[clean] stage2 切段中…")
    s2 = stage2_segment(parquet_path, out)
    manifest = {
        "params": {
            "wuhan_bbox": WUHAN_BBOX, "ref": [LON0, LAT0],
            "move_thresh_kn": MOVE_THRESH_KN, "stat_run_s": STAT_RUN_S, "gap_max_s": GAP_MAX_S,
            "min_points": MIN_POINTS, "min_span_m": MIN_SPAN_M,
            "bend_turn_deg_s": BEND_TURN_DEG_S, "min_dt_s": MIN_DT_S,
            "min_dist_m": MIN_DIST_M, "max_turn_deg_s": MAX_TURN_DEG_S,
            "thin_spacing_s": THIN_SPACING_S,
        },
        "stage1": s1, "stage2": s2,
    }
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2),
                                       encoding="utf-8")
    print(f"[clean] 完成。segments={s2['segments']:,} vessels={s2['vessels']:,} "
          f"bend_frac={s2['bend_point_fraction']}")
    print(f"[clean] 数据集: {out/'segments.parquet'}  manifest: {out/'manifest.json'}")


if __name__ == "__main__":
    main()
