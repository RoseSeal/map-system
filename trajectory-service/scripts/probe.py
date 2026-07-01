#!/usr/bin/env python3
"""step-1 数据探查 (probe) — NBSDC 内河 AIS 数据集.

go/no-go 闸门：确认数据可用，并为 step-2 清洗 / step-3 会遇挖掘定参数。
对照 docs/v1/step-1.md 的交付物清单产出统计、抽样轨迹图与数据契约核实点。

用法:
    python scripts/probe.py --csv <ais.csv> --out runtime/probe
    [--chunksize 1000000] [--sample-mmsi 6] [--min-points 200]
    [--moving-sog-min 1.0]

设计：
  - 全表分块扫描（pass1）累加全局统计，内存有界；丢弃 NMEA 原始报文列以省内存。
  - 第二遍（pass2）按武汉范围内航行点数挑若干 MMSI，重读筛选其轨迹作图并算精确采样间隔。
  - 不做任何清洗 / 建模 / 落数据集（见 step-1 scope ceiling）。
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
from collections import Counter
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

# 逻辑列名 -> 候选实际列名（小写；exact 优先，contains 兜底）
COLUMN_CANDIDATES = {
    "mmsi": ["mmsi"],
    "msgtime": ["msgtime", "msg_time", "报文时间"],
    "timestamp_slot": ["timestamp"],
    "lon": ["lon", "longitude", "经度"],
    "lat": ["lat", "latitude", "纬度"],
    "sog": ["speedoverground", "sog", "对地航速"],
    "cog": ["courseoverground", "cog", "对地航向"],
    "heading": ["trueheading", "heading", "真艏向", "船艏向"],
    "rot": ["rateofturn", "rot", "转向率"],
    "navstatus": ["navigationalstatus", "航行状态"],
}

NUMERIC_KEYS = ("sog", "cog", "rot", "heading", "lon", "lat", "timestamp_slot")
RESERVOIR_CAP = 500_000  # 每列蓄水池全局上限，保证内存有界（与分块数无关）
WUHAN_BBOX = {
    "lon_min": 113.5,
    "lon_max": 115.1,
    "lat_min": 29.8,
    "lat_max": 31.0,
}
VESSEL_MMSI_PREFIXES = ("412", "413")


def sniff(path: Path, n_bytes: int = 65536):
    """探测编码与分隔符，返回 (encoding, sep, preview_lines)。中文 AIS 常为 gbk/gb18030。"""
    raw = path.open("rb").read(n_bytes)
    text, encoding = None, None
    for enc in ("utf-8-sig", "utf-8", "gb18030", "gbk"):
        try:
            text = raw.decode(enc)
            encoding = enc
            break
        except UnicodeDecodeError:
            continue
    if text is None:
        encoding, text = "utf-8", raw.decode("utf-8", errors="replace")
    lines = text.splitlines()
    try:
        sep = csv.Sniffer().sniff("\n".join(lines[:20]), delimiters=",\t;|").delimiter
    except csv.Error:
        sep = ","
    return encoding, sep, lines[:5]


def resolve_columns(actual_cols) -> dict:
    """逻辑列名 -> 实际列名。exact 优先，contains 兜底。"""
    lowered = {c: str(c).strip().lower() for c in actual_cols}
    mapping: dict = {}
    for key, cands in COLUMN_CANDIDATES.items():
        found = next((c for c, lc in lowered.items() if lc in cands), None)
        if found is None:
            found = next((c for c, lc in lowered.items() if any(x in lc for x in cands)), None)
        if found is not None:
            mapping[key] = found
    return mapping


def reservoir_add(store: list, values: np.ndarray, per_chunk_cap: int, rng) -> None:
    """每列有界蓄水池：每块抽样追加后，越过全局上限即整体随机下采样，内存与分块数无关。"""
    values = values[~np.isnan(values)]
    if values.size == 0:
        return
    if values.size > per_chunk_cap:
        values = rng.choice(values, per_chunk_cap, replace=False)
    store.extend(values.tolist())
    if len(store) > RESERVOIR_CAP:
        store[:] = rng.choice(store, RESERVOIR_CAP, replace=False).tolist()


def describe(values: list) -> dict:
    if not values:
        return {}
    arr = np.asarray(values, dtype=float)
    qs = np.nanpercentile(arr, [0, 1, 25, 50, 75, 99, 100])
    int_frac = float(np.mean(np.isclose(arr, np.round(arr))))
    return {
        "n": int(arr.size),
        "min": float(qs[0]), "p1": float(qs[1]), "p25": float(qs[2]),
        "median": float(qs[3]), "p75": float(qs[4]), "p99": float(qs[5]),
        "max": float(qs[6]), "integer_fraction": int_frac,
    }


def valid_mmsi_mask(series: pd.Series) -> pd.Series:
    """合法 MMSI 为 9 位数字；其他短码 / 测试码在清洗阶段剔除。"""
    return series.astype(str).str.strip().str.fullmatch(r"\d{9}", na=False)


def vessel_mmsi_mask(series: pd.Series) -> pd.Series:
    """默认抽样中国普通船舶 MMSI，避开 99x 等非普通船舶标识。"""
    normalized = series.astype(str).str.strip()
    return valid_mmsi_mask(series) & normalized.str.startswith(VESSEL_MMSI_PREFIXES)


def wuhan_bbox_mask(lon: pd.Series, lat: pd.Series) -> pd.Series:
    return (
        lon.between(WUHAN_BBOX["lon_min"], WUHAN_BBOX["lon_max"], inclusive="both")
        & lat.between(WUHAN_BBOX["lat_min"], WUHAN_BBOX["lat_max"], inclusive="both")
    )


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", required=True, help="NBSDC AIS CSV 路径")
    ap.add_argument("--out", default="runtime/probe", help="输出目录")
    ap.add_argument("--chunksize", type=int, default=1_000_000)
    ap.add_argument("--sample-mmsi", type=int, default=6, help="抽样作图的 MMSI 数")
    ap.add_argument("--min-points", type=int, default=200, help="抽样 MMSI 的最小点数")
    ap.add_argument("--moving-sog-min", type=float, default=1.0, help="航行船抽样的 SOG 下限（节）")
    args = ap.parse_args()

    csv_path = Path(args.csv)
    out = Path(args.out)
    (out / "plots").mkdir(parents=True, exist_ok=True)
    if not csv_path.exists():
        sys.exit(f"[probe] 找不到数据文件: {csv_path}")

    size_gb = csv_path.stat().st_size / 1e9
    encoding, sep, preview = sniff(csv_path)
    print(f"[probe] file={csv_path.name} size={size_gb:.2f}GB encoding={encoding} sep={sep!r}")
    print("[probe] 原始前 5 行:")
    for line in preview:
        print("   ", line[:300])

    header = pd.read_csv(csv_path, sep=sep, encoding=encoding, nrows=5)
    colmap = resolve_columns(header.columns)
    inv = {v: k for k, v in colmap.items()}
    print(f"[probe] 列映射: {colmap}")
    missing = [k for k in ("mmsi", "msgtime", "lon", "lat") if k not in colmap]
    if missing:
        sys.exit(f"[probe] 关键列未识别: {missing}; 实际列={list(header.columns)}")

    # ---- pass1: 全表分块累加 ----
    total = 0
    mmsi_counts: Counter = Counter()
    moving_mmsi_counts: Counter = Counter()
    mmsi_tmin: dict = {}
    mmsi_tmax: dict = {}
    bbox = [np.inf, np.inf, -np.inf, -np.inf]  # lon_min, lat_min, lon_max, lat_max
    t_min = t_max = None
    null_counts: Counter = Counter()
    quality_counts: Counter = Counter()
    reservoir = {k: [] for k in NUMERIC_KEYS}
    ts_slot_sample: list = []
    msgtime_sample: list = []
    rng = np.random.default_rng(42)

    reader = pd.read_csv(
        csv_path, sep=sep, encoding=encoding,
        usecols=list(colmap.values()), chunksize=args.chunksize,
    )
    for chunk in reader:
        chunk = chunk.rename(columns=inv)
        total += len(chunk)
        for k in colmap:
            null_counts[k] += int(chunk[k].isna().sum()) if k in chunk else 0

        t = pd.to_datetime(chunk["msgtime"], errors="coerce")
        if t.notna().any():
            cmin, cmax = t.min(), t.max()
            t_min = cmin if t_min is None else min(t_min, cmin)
            t_max = cmax if t_max is None else max(t_max, cmax)

        lon = pd.to_numeric(chunk["lon"], errors="coerce")
        lat = pd.to_numeric(chunk["lat"], errors="coerce")
        sog = pd.to_numeric(chunk["sog"], errors="coerce") if "sog" in chunk else pd.Series(np.nan, index=chunk.index)
        bbox[0] = min(bbox[0], float(np.nanmin(lon))) if lon.notna().any() else bbox[0]
        bbox[1] = min(bbox[1], float(np.nanmin(lat))) if lat.notna().any() else bbox[1]
        bbox[2] = max(bbox[2], float(np.nanmax(lon))) if lon.notna().any() else bbox[2]
        bbox[3] = max(bbox[3], float(np.nanmax(lat))) if lat.notna().any() else bbox[3]
        legal_mmsi = valid_mmsi_mask(chunk["mmsi"])
        vessel_mmsi = vessel_mmsi_mask(chunk["mmsi"])
        in_wuhan = wuhan_bbox_mask(lon, lat)
        moving = vessel_mmsi & in_wuhan & (sog > args.moving_sog_min)
        quality_counts["legal_mmsi_rows"] += int(legal_mmsi.sum())
        quality_counts["vessel_mmsi_rows"] += int(vessel_mmsi.sum())
        quality_counts["wuhan_bbox_rows"] += int(in_wuhan.sum())
        quality_counts["legal_wuhan_rows"] += int((legal_mmsi & in_wuhan).sum())
        quality_counts["vessel_wuhan_rows"] += int((vessel_mmsi & in_wuhan).sum())
        quality_counts["moving_candidate_rows"] += int(moving.sum())

        mmsi_counts.update(chunk["mmsi"].value_counts().to_dict())
        moving_mmsi_counts.update(chunk.loc[moving, "mmsi"].value_counts().to_dict())
        agg = chunk.assign(_t=t).groupby("mmsi")["_t"].agg(["min", "max"])
        for m, vmin in agg["min"].items():
            if pd.notna(vmin) and (m not in mmsi_tmin or vmin < mmsi_tmin[m]):
                mmsi_tmin[m] = vmin
        for m, vmax in agg["max"].items():
            if pd.notna(vmax) and (m not in mmsi_tmax or vmax > mmsi_tmax[m]):
                mmsi_tmax[m] = vmax

        for k in NUMERIC_KEYS:
            if k in chunk:
                reservoir_add(reservoir[k], pd.to_numeric(chunk[k], errors="coerce").to_numpy(), 50_000, rng)
        if "timestamp_slot" in chunk and len(ts_slot_sample) < 50:
            ts_slot_sample.extend(chunk["timestamp_slot"].dropna().head(20).tolist())
        if len(msgtime_sample) < 10:
            msgtime_sample.extend(chunk["msgtime"].dropna().head(5).astype(str).tolist())

    # 每船平均采样间隔（粗）：(tmax - tmin) / (count - 1)
    avg_intervals = []
    for m, n in mmsi_counts.items():
        if n > 1 and m in mmsi_tmin and m in mmsi_tmax:
            span = (mmsi_tmax[m] - mmsi_tmin[m]).total_seconds()
            if span > 0:
                avg_intervals.append(span / (n - 1))
    counts_arr = np.array(list(mmsi_counts.values()), dtype=float)

    report = {
        "file": csv_path.name,
        "size_gb": round(size_gb, 3),
        "encoding": encoding,
        "sep": sep,
        "total_rows": total,
        "unique_mmsi": len(mmsi_counts),
        "column_mapping": colmap,
        "null_rate": {k: round(null_counts[k] / total, 5) for k in colmap},
        "time_span": {
            "min": str(t_min), "max": str(t_max),
            "days": round((t_max - t_min).total_seconds() / 86400, 2) if t_min and t_max else None,
        },
        "bbox": {"lon_min": bbox[0], "lat_min": bbox[1], "lon_max": bbox[2], "lat_max": bbox[3]},
        "per_mmsi_rowcount": {
            "min": float(counts_arr.min()), "median": float(np.median(counts_arr)),
            "p90": float(np.percentile(counts_arr, 90)), "max": float(counts_arr.max()),
        },
        "quality_filters": {
            "wuhan_bbox": WUHAN_BBOX,
            "vessel_mmsi_prefixes": list(VESSEL_MMSI_PREFIXES),
            "moving_sog_min": args.moving_sog_min,
            "legal_mmsi_rows": quality_counts["legal_mmsi_rows"],
            "vessel_mmsi_rows": quality_counts["vessel_mmsi_rows"],
            "wuhan_bbox_rows": quality_counts["wuhan_bbox_rows"],
            "legal_wuhan_rows": quality_counts["legal_wuhan_rows"],
            "vessel_wuhan_rows": quality_counts["vessel_wuhan_rows"],
            "moving_candidate_rows": quality_counts["moving_candidate_rows"],
            "legal_mmsi_rate": round(quality_counts["legal_mmsi_rows"] / total, 5),
            "vessel_mmsi_rate": round(quality_counts["vessel_mmsi_rows"] / total, 5),
            "wuhan_bbox_rate": round(quality_counts["wuhan_bbox_rows"] / total, 5),
            "legal_wuhan_rate": round(quality_counts["legal_wuhan_rows"] / total, 5),
            "vessel_wuhan_rate": round(quality_counts["vessel_wuhan_rows"] / total, 5),
            "moving_candidate_rate": round(quality_counts["moving_candidate_rows"] / total, 5),
            "moving_candidate_unique_mmsi": len(moving_mmsi_counts),
        },
        "avg_sampling_interval_sec": describe(avg_intervals),
        "distributions": {k: describe(reservoir[k]) for k in NUMERIC_KEYS},
        "timestamp_slot_sample": [str(x) for x in ts_slot_sample[:20]],
        "msgtime_sample": msgtime_sample[:10],
    }

    # 分布直方图
    for k in NUMERIC_KEYS:
        if reservoir[k]:
            plt.figure(figsize=(5, 3))
            plt.hist(reservoir[k], bins=80)
            plt.title(f"{k} distribution (sampled)")
            plt.tight_layout()
            plt.savefig(out / "plots" / f"dist_{k}.png", dpi=110)
            plt.close()

    for stale in (out / "plots").glob("moving_traj_*.png"):
        stale.unlink()

    # ---- pass2: 抽样航行 MMSI 作轨迹图 + 精确采样间隔 ----
    candidates = [m for m, n in moving_mmsi_counts.most_common() if n >= args.min_points]
    sample_ids = candidates[: args.sample_mmsi]
    if sample_ids:
        want = set(sample_ids)
        frames: dict = {m: [] for m in sample_ids}
        reader = pd.read_csv(
            csv_path, sep=sep, encoding=encoding,
            usecols=list(colmap.values()), chunksize=args.chunksize,
        )
        for chunk in reader:
            chunk = chunk.rename(columns=inv)
            hit = chunk[chunk["mmsi"].isin(want)]
            for m, sub in hit.groupby("mmsi"):
                frames[m].append(sub)
        precise_dt = {}
        for m in sample_ids:
            df = pd.concat(frames[m], ignore_index=True)
            df["_t"] = pd.to_datetime(df["msgtime"], errors="coerce")
            df["_lon"] = pd.to_numeric(df["lon"], errors="coerce")
            df["_lat"] = pd.to_numeric(df["lat"], errors="coerce")
            df["_sog"] = pd.to_numeric(df["sog"], errors="coerce") if "sog" in df else np.nan
            df = df.dropna(subset=["_t", "_lon", "_lat", "_sog"])
            df = df[
                vessel_mmsi_mask(df["mmsi"])
                & wuhan_bbox_mask(df["_lon"], df["_lat"])
                & (df["_sog"] > args.moving_sog_min)
            ].sort_values("_t")
            dt = df["_t"].diff().dt.total_seconds().dropna()
            precise_dt[str(m)] = describe(dt.tolist())
            plt.figure(figsize=(5, 5))
            segments = df["_t"].diff().dt.total_seconds().gt(600).cumsum()
            for _, seg in df.groupby(segments):
                if len(seg) > 1:
                    plt.plot(seg["_lon"], seg["_lat"], color="#9aa0a6", lw=0.5, alpha=0.45)
            sc = plt.scatter(df["_lon"], df["_lat"], c=df["_sog"], s=4, cmap="viridis")
            plt.colorbar(sc, label="SOG (kn)")
            plt.title(f"Moving MMSI {m}  (n={len(df)}, SOG>{args.moving_sog_min:g}kn)")
            plt.xlabel("lon"); plt.ylabel("lat")
            plt.tight_layout()
            plt.savefig(out / "plots" / f"moving_traj_{m}.png", dpi=110)
            plt.close()
        report["sampled_moving_mmsi"] = [str(m) for m in sample_ids]
        report["moving_sampling_interval_sec"] = precise_dt

    (out / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    _write_markdown(out / "report.md", report)
    print(f"[probe] 完成。报告: {out/'report.md'}  图: {out/'plots'}")
    print("[probe] go/no-go 关键项见 report.md 顶部清单。")


def _write_markdown(path: Path, r: dict) -> None:
    L = [
        "# step-1 数据探查报告（probe）",
        "",
        "## go/no-go 关键项",
        f"- 总行数: **{r['total_rows']:,}** | 唯一 MMSI: **{r['unique_mmsi']:,}** | 体量: {r['size_gb']}GB",
        f"- 时间跨度: {r['time_span']['min']} ~ {r['time_span']['max']}（约 {r['time_span']['days']} 天）",
        f"- bbox: lon [{r['bbox']['lon_min']}, {r['bbox']['lon_max']}], "
        f"lat [{r['bbox']['lat_min']}, {r['bbox']['lat_max']}] —— 核对是否落在目标内河水域",
        f"- 列映射: `{r['column_mapping']}`",
        "",
        "## 航行船抽样过滤",
        f"- 武汉范围: `{r['quality_filters']['wuhan_bbox']}`",
        f"- 船舶 MMSI 前缀: {r['quality_filters']['vessel_mmsi_prefixes']}；航行点阈值: "
        f"SOG > {r['quality_filters']['moving_sog_min']} kn",
        f"- 合法 MMSI 行: {r['quality_filters']['legal_mmsi_rows']:,} "
        f"({r['quality_filters']['legal_mmsi_rate']})",
        f"- 船舶 MMSI 行: {r['quality_filters']['vessel_mmsi_rows']:,} "
        f"({r['quality_filters']['vessel_mmsi_rate']})",
        f"- 武汉范围行: {r['quality_filters']['wuhan_bbox_rows']:,} "
        f"({r['quality_filters']['wuhan_bbox_rate']})",
        f"- 合法 MMSI 且武汉范围行: {r['quality_filters']['legal_wuhan_rows']:,} "
        f"({r['quality_filters']['legal_wuhan_rate']})",
        f"- 船舶 MMSI 且武汉范围行: {r['quality_filters']['vessel_wuhan_rows']:,} "
        f"({r['quality_filters']['vessel_wuhan_rate']})",
        f"- 航行候选行: {r['quality_filters']['moving_candidate_rows']:,} "
        f"({r['quality_filters']['moving_candidate_rate']}); "
        f"候选 MMSI: {r['quality_filters']['moving_candidate_unique_mmsi']:,}",
        f"- 航行船抽样 MMSI: {r.get('sampled_moving_mmsi', [])}",
        "",
        "## 数据契约核实点",
        f"- MSGTIME 样例: {r['msgtime_sample']}",
        f"- TimeStamp 字段样例: {r['timestamp_slot_sample']}",
        f"- TimeStamp 数值分布: {r['distributions'].get('timestamp_slot', {})} "
        "—— 若 max≤59 则为 AIS 时隙秒（不可用于排序），否则可能是可用时间字段",
        f"- RateOfTurn 分布: {r['distributions'].get('rot', {})} "
        "—— integer_fraction 接近 1 且范围 [-128,127] 多半是 AIS 原始编码",
        "",
        "## 采样间隔（决定预测时域）",
        f"- 每船平均间隔(秒): {r['avg_sampling_interval_sec']}",
        f"- 航行船抽样精确间隔: {r.get('moving_sampling_interval_sec', {})}",
        "",
        "## 缺失率",
    ]
    for k, v in r["null_rate"].items():
        L.append(f"- {k}: {v}")
    L += [
        "",
        "## 每船记录数分布",
        f"- min {r['per_mmsi_rowcount']['min']} / median {r['per_mmsi_rowcount']['median']} "
        f"/ p90 {r['per_mmsi_rowcount']['p90']} / max {r['per_mmsi_rowcount']['max']}",
        "",
        f"航行船抽样轨迹图: `plots/moving_traj_*.png`；分布直方图: `plots/dist_*.png`。",
    ]
    path.write_text("\n".join(L), encoding="utf-8")


if __name__ == "__main__":
    main()
