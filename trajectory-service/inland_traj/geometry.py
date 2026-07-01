"""inland_traj.geometry — 数据无关的几何核心 (step-4).

提供：局部平面投影、CV 外推、ADE/FDE、CPA/TCPA（解析 + 数值）、风险等级映射、
"预测 → 风险误差"下游指标。

约定：
  - 距离一律以**米**计；速度以 **m/s**；航向角用**罗经角**（0=正北，顺时针为正）。
  - 局部平面坐标 (x, y) = (东 East, 北 North)，以某参考经纬度为原点等距圆柱近似。
"""
from __future__ import annotations

import math

import numpy as np

EARTH_R = 6_371_000.0  # 地球半径（米）
NM = 1852.0            # 1 海里 = 米
KNOT = 0.514444        # 1 节 = m/s

# 风险阈值（取自 map-system 演示口径）：(等级, DCPA 上限米, TCPA 上限秒)
RISK_THRESHOLDS = [
    ("ALARM", 0.3 * NM, 300),
    ("WARNING", 0.5 * NM, 900),
    ("CAUTION", 1.0 * NM, 1800),
]
DANGER_LEVELS = {"ALARM", "WARNING"}  # 视为"需告警"的等级


def to_local_xy(lon, lat, lon0, lat0):
    """经纬度 → 以 (lon0, lat0) 为原点的局部平面 (x=东, y=北) 米。等距圆柱近似，小范围足够。"""
    lon = np.asarray(lon, dtype=float)
    lat = np.asarray(lat, dtype=float)
    x = np.radians(lon - lon0) * EARTH_R * math.cos(math.radians(lat0))
    y = np.radians(lat - lat0) * EARTH_R
    return x, y


def velocity_from_sog_cog(sog_knots, cog_deg):
    """SOG（节）+ COG（罗经度）→ (vx 东, vy 北) m/s。"""
    v = np.asarray(sog_knots, dtype=float) * KNOT
    th = np.radians(np.asarray(cog_deg, dtype=float))
    return v * np.sin(th), v * np.cos(th)


def cv_predict(xy_hist, t_hist, horizons_s, window=3):
    """恒速（CV）外推：用历史末段最小二乘估速度，线性外推到各预测时域。

    xy_hist: (N,2) 米；t_hist: (N,) 秒；horizons_s: 可迭代的预测时域（秒，相对最后一点）。
    返回 (H,2) 预测点。历史不足两点时速度取 0（退化为持点）。
    """
    xy = np.asarray(xy_hist, dtype=float)
    t = np.asarray(t_hist, dtype=float)
    horizons = np.asarray(list(horizons_s), dtype=float)
    p0 = xy[-1]
    k = min(window, len(xy))
    if k >= 2:
        tt = t[-k:] - t[-1]
        vx = np.polyfit(tt, xy[-k:, 0], 1)[0]
        vy = np.polyfit(tt, xy[-k:, 1], 1)[0]
    else:
        vx = vy = 0.0
    return np.column_stack([p0[0] + vx * horizons, p0[1] + vy * horizons])


def _check_pair(a, b, name):
    """校验两条轨迹为非空、同形的 (H,2)，不满足抛 ValueError（拒绝静默截断）。"""
    a = np.asarray(a, dtype=float)
    b = np.asarray(b, dtype=float)
    if a.ndim != 2 or a.shape[1] != 2 or a.shape[0] == 0 or a.shape != b.shape:
        raise ValueError(f"{name}: 需要两个非空且同形的 (H,2) 数组，实得 {a.shape} vs {b.shape}")
    return a, b


def ade_fde(pred, gt):
    """预测与真值轨迹（均 (H,2) 米，须同形）→ (ADE, FDE) 米。ADE=逐点 L2 均值，FDE=末点 L2。"""
    pred, gt = _check_pair(pred, gt, "ade_fde")
    d = np.linalg.norm(pred - gt, axis=1)
    return float(d.mean()), float(d[-1])


def cpa_tcpa_analytic(p_o, v_o, p_t, v_t):
    """闭式 CPA/TCPA（恒速口径）。

    p_*: (2,) 位置米；v_*: (2,) 速度 m/s。返回 (DCPA 米, TCPA 秒)。
    TCPA<0 表示最近点在过去（正在远离），此时 DCPA 取当前距离。
    """
    r = np.asarray(p_t, dtype=float) - np.asarray(p_o, dtype=float)
    v = np.asarray(v_t, dtype=float) - np.asarray(v_o, dtype=float)
    vv = float(v @ v)
    if vv < 1e-9:  # 相对静止
        return float(np.linalg.norm(r)), 0.0
    tcpa = -float(r @ v) / vv
    if tcpa < 0:
        return float(np.linalg.norm(r)), tcpa
    rc = r + v * tcpa
    return float(np.linalg.norm(rc)), tcpa


def cpa_tcpa_numerical(traj_o, traj_t, times_s):
    """数值 CPA/TCPA：两条时间对齐轨迹（均 (H,2) 米）+ 显式时间 times_s（H, 秒）→ (DCPA 米, TCPA 秒)。

    按相邻采样点间的**分段线性相对运动**逐段闭式求最近点，再取全局最小——既用真实时间
    （避免把首点 TCPA 错报成 0），又能抓住采样点之间的交叉（避免离散漏检）。
    适用任意（含曲线）预测路径；对真值与各预测器用同一方法以保证可比。
    times_s 应单调、从"现在"起算；若需把当前点纳入，调用方应把 t=0 的当前位置作为首样本。
    """
    a, b = _check_pair(traj_o, traj_t, "cpa_tcpa_numerical")
    t = np.asarray(times_s, dtype=float)
    if t.shape != (a.shape[0],):
        raise ValueError(f"times_s 形状须为 ({a.shape[0]},)，实得 {t.shape}")
    rel = b - a  # 相对位置 r_k
    best_d = float(np.linalg.norm(rel[0]))
    best_t = float(t[0])
    for k in range(len(t) - 1):
        p = rel[k]
        d = rel[k + 1] - rel[k]
        dd = float(d @ d)
        u = 0.0 if dd < 1e-12 else min(1.0, max(0.0, -float(p @ d) / dd))
        dist = float(np.linalg.norm(p + u * d))
        if dist < best_d:
            best_d = dist
            best_t = float(t[k] + u * (t[k + 1] - t[k]))
    return best_d, best_t


def risk_level(dcpa_m, tcpa_s):
    """(DCPA 米, TCPA 秒) → 风险等级。TCPA<0（远离）记 SAFE。"""
    if tcpa_s is None or tcpa_s < 0:
        return "SAFE"
    for name, dmax, tmax in RISK_THRESHOLDS:
        if dcpa_m <= dmax and tcpa_s <= tmax:
            return name
    return "SAFE"


def downstream_risk_error(pred_o, pred_t, gt_o, gt_t, times_s):
    """下游"预测 → 风险误差"指标。

    输入预测与真值的双船未来轨迹（均 (H,2) 米）+ 显式时间 times_s（H, 秒）。
    以真值为参照，返回 DCPA/TCPA 误差、风险等级是否一致、以及是否误报 / 漏报。
    """
    d_pred, t_pred = cpa_tcpa_numerical(pred_o, pred_t, times_s)
    d_gt, t_gt = cpa_tcpa_numerical(gt_o, gt_t, times_s)
    r_pred = risk_level(d_pred, t_pred)
    r_gt = risk_level(d_gt, t_gt)
    return {
        "dcpa_pred": d_pred, "dcpa_gt": d_gt, "dcpa_err": abs(d_pred - d_gt),
        "tcpa_pred": t_pred, "tcpa_gt": t_gt, "tcpa_err": abs(t_pred - t_gt),
        "risk_pred": r_pred, "risk_gt": r_gt, "risk_match": r_pred == r_gt,
        "false_alarm": (r_pred in DANGER_LEVELS) and (r_gt not in DANGER_LEVELS),
        "miss": (r_pred not in DANGER_LEVELS) and (r_gt in DANGER_LEVELS),
    }
