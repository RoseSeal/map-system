"""step-4 几何核心单测：每个函数用已知算例钉死正确性。"""
import math

import numpy as np
import pytest

from inland_traj import geometry as g


def test_to_local_xy_origin_and_east():
    # 原点处为 (0,0)
    x, y = g.to_local_xy(120.0, 30.0, 120.0, 30.0)
    assert abs(float(x)) < 1e-6 and abs(float(y)) < 1e-6
    # 向东 0.001 度，应得正的 x（东），y≈0
    x, y = g.to_local_xy(120.001, 30.0, 120.0, 30.0)
    expected_x = math.radians(0.001) * g.EARTH_R * math.cos(math.radians(30.0))
    assert float(x) == pytest.approx(expected_x, rel=1e-6)
    assert abs(float(y)) < 1e-6


def test_velocity_from_sog_cog_directions():
    # COG=0（正北）→ vy>0, vx≈0
    vx, vy = g.velocity_from_sog_cog(10.0, 0.0)
    assert abs(float(vx)) < 1e-9 and float(vy) == pytest.approx(10 * g.KNOT)
    # COG=90（正东）→ vx>0, vy≈0
    vx, vy = g.velocity_from_sog_cog(10.0, 90.0)
    assert float(vx) == pytest.approx(10 * g.KNOT) and abs(float(vy)) < 1e-9


def test_cv_predict_constant_velocity_exact():
    # 沿东 10 m/s 的恒速历史，外推应精确落在直线上
    t = [0, 10, 20]
    xy = [[0, 0], [100, 0], [200, 0]]
    pred = g.cv_predict(xy, t, horizons_s=[10, 20])
    assert pred[0] == pytest.approx([300, 0])
    assert pred[1] == pytest.approx([400, 0])


def test_cv_predict_single_point_degenerates():
    pred = g.cv_predict([[5, 5]], [0], horizons_s=[10, 20])
    assert pred[0] == pytest.approx([5, 5])
    assert pred[1] == pytest.approx([5, 5])


def test_ade_fde_known():
    pred = [[0, 0], [0, 0]]
    gt = [[3, 4], [6, 8]]  # L2 = 5, 10
    ade, fde = g.ade_fde(pred, gt)
    assert ade == pytest.approx(7.5)
    assert fde == pytest.approx(10.0)


def test_cpa_tcpa_analytic_crossing():
    # own (0,0) 东行 10；target (100,50) 西行 10 → TCPA=5s, DCPA=50
    dcpa, tcpa = g.cpa_tcpa_analytic([0, 0], [10, 0], [100, 50], [-10, 0])
    assert tcpa == pytest.approx(5.0)
    assert dcpa == pytest.approx(50.0)


def test_cpa_tcpa_analytic_head_on_collision():
    # own (0,0) 东行 10；target (200,0) 西行 10 → TCPA=10s, DCPA=0
    dcpa, tcpa = g.cpa_tcpa_analytic([0, 0], [10, 0], [200, 0], [-10, 0])
    assert tcpa == pytest.approx(10.0)
    assert dcpa == pytest.approx(0.0, abs=1e-6)


def test_cpa_tcpa_analytic_diverging_negative_tcpa():
    # target 在身后且更快远离 → TCPA<0，DCPA=当前距离
    dcpa, tcpa = g.cpa_tcpa_analytic([0, 0], [10, 0], [-100, 0], [-10, 0])
    assert tcpa < 0
    assert dcpa == pytest.approx(100.0)


def test_analytic_matches_numerical_on_straight_paths():
    # 两条恒速直线轨迹，数值 CPA 应与解析一致（步长足够细）
    dt = 1.0
    h = np.arange(0, 30, dt)
    traj_o = np.column_stack([10 * h, 0 * h])          # 东行 10
    traj_t = np.column_stack([100 - 10 * h, 50 + 0 * h])  # 西行 10，偏北 50
    d_num, t_num = g.cpa_tcpa_numerical(traj_o, traj_t, h)
    d_ana, t_ana = g.cpa_tcpa_analytic([0, 0], [10, 0], [100, 50], [-10, 0])
    assert d_num == pytest.approx(d_ana, abs=1e-6)
    assert t_num == pytest.approx(t_ana, abs=1e-6)


def test_numerical_catches_subsample_crossing():
    # 两船在采样点之间交叉，真实 DCPA≈0；离散取点会漏掉，分段闭式能抓住
    times = [0.0, 10.0]
    traj_o = [[0, 0], [0, 0]]          # 本船静止
    traj_t = [[-50, 5], [50, -5]]      # 相对运动恰过原点
    d, t = g.cpa_tcpa_numerical(traj_o, traj_t, times)
    assert d == pytest.approx(0.0, abs=1e-6)
    assert t == pytest.approx(5.0, abs=1e-6)


def test_metric_shape_validation():
    with pytest.raises(ValueError):
        g.ade_fde([[0, 0], [1, 1]], [[0, 0]])             # 长度不一致
    with pytest.raises(ValueError):
        g.cpa_tcpa_numerical([], [], [])                  # 空
    with pytest.raises(ValueError):
        g.cpa_tcpa_numerical([[0, 0]], [[0, 0]], [0, 0])  # times 长度不符


def test_risk_level_thresholds():
    assert g.risk_level(0.2 * g.NM, 200) == "ALARM"
    assert g.risk_level(0.4 * g.NM, 800) == "WARNING"
    assert g.risk_level(0.9 * g.NM, 1700) == "CAUTION"
    assert g.risk_level(2.0 * g.NM, 100) == "SAFE"   # DCPA 太大
    assert g.risk_level(0.1 * g.NM, -5) == "SAFE"    # 正在远离


def test_downstream_false_alarm_when_cv_misjudges_bend():
    # 真值：target 在公里级尺度上转弯远离 → DCPA>1nm，实际 SAFE。
    # CV 预测：直线径直冲向本船 → DCPA≈0，误判为 ALARM → 应触发 false_alarm。
    dt = 10.0
    n = 30
    t = np.arange(n) * dt  # 0..290 秒
    own = np.zeros((n, 2))  # 本船静止于原点
    s = np.sin(np.linspace(0, math.pi / 2, n))
    tgt_gt = np.column_stack([3000 - 1000 * s, 2500 * s])  # 转向远离，DCPA≈2.8km → SAFE
    tgt_pred = np.column_stack([3000 - 15 * t, np.zeros(n)])  # 直线冲向原点 → ALARM
    out = g.downstream_risk_error(own, tgt_pred, own, tgt_gt, t)
    assert out["dcpa_gt"] > out["dcpa_pred"]   # 真值更安全
    assert out["risk_gt"] == "SAFE"
    assert out["risk_pred"] in g.DANGER_LEVELS
    assert out["false_alarm"] is True


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-q"]))
