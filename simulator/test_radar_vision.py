# -*- coding: utf-8 -*-
"""雷达/视觉两路 MQTT 观测模拟与离线核对的单元测试。

覆盖：seed 重现、独立源、丢帧 0/1、无噪声、关闭 AIS、jump/dropout 不污染真值、
非法参数/消息、时间对齐、贪心匹配反例、输出覆盖拒绝。
仅使用标准库 + 已有 paho，不引入新依赖。
"""
import hashlib
import json
import math
import os
import shutil
import tempfile
import unittest
from datetime import datetime, timezone, timedelta
from pathlib import Path

from wuhan_yangtze_ais_simulator import (
    SimulationConfig,
    simulate_offline,
    iso_z,
    parse_epoch,
    add_position_noise,
    haversine_m,
    build_routes,
    WuhanPublisher,
    parse_injection,
    _parse_sensors,
)
from mqtt_publisher_base import MqttConfig
from verify_sensor_observations import (
    verify_observations,
    load_jsonl,
    match_rectangular,
)


def _cfg(**kw):
    """构造一个 60 tick、seed=42、epoch 固定的离线配置，便于覆盖测试。"""
    base = dict(
        sensors=("ais", "radar", "vision"),
        seed=42,
        epoch=parse_epoch("2026-11-26T04:00:00Z"),
        interval=1.0,
        ticks=60,
        start_at=0.0,
        radar_noise_m=8.0,
        vision_noise_m=15.0,
        radar_drop_rate=0.1,
        vision_drop_rate=0.2,
        ais_off=("900002222",),
    )
    base.update(kw)
    return SimulationConfig(**base)


class SeedReproTest(unittest.TestCase):
    def test_same_seed_reproduces(self):
        a = simulate_offline(_cfg())
        b = simulate_offline(_cfg())
        self.assertEqual(a["messages"], b["messages"])
        self.assertEqual(a["truth"], b["truth"])

    def test_different_seed_changes_noise(self):
        a = simulate_offline(_cfg(seed=42))
        b = simulate_offline(_cfg(seed=7))
        radar_a = [m for m in a["messages"] if m["topic"] == "usv/RadarObservation"]
        radar_b = [m for m in b["messages"] if m["topic"] == "usv/RadarObservation"]
        self.assertNotEqual(radar_a, radar_b)


class IndependentSourceTest(unittest.TestCase):
    def test_radar_and_vision_independent(self):
        # 无丢帧、有噪声，同 tick 同目标的雷达与视觉观测应几乎总是不同。
        res = simulate_offline(_cfg(radar_drop_rate=0.0, vision_drop_rate=0.0))
        radar = [m for m in res["messages"] if m["topic"] == "usv/RadarObservation"]
        vision = [m for m in res["messages"] if m["topic"] == "usv/VisionObservation"]
        self.assertEqual(len(radar), len(vision))
        diff = 0
        for r, v in zip(radar, vision):
            if (r["payload"]["latitude"], r["payload"]["longitude"]) != (
                v["payload"]["latitude"],
                v["payload"]["longitude"],
            ):
                diff += 1
        self.assertGreater(diff, len(radar) * 0.9)


class DropRateTest(unittest.TestCase):
    def test_drop_rate_zero_sends_all(self):
        res = simulate_offline(_cfg(radar_drop_rate=0.0, vision_drop_rate=0.0))
        radar = [m for m in res["messages"] if m["topic"] == "usv/RadarObservation"]
        # 3 艘船 * 60 tick
        self.assertEqual(len(radar), 3 * 60)

    def test_drop_rate_one_sends_none(self):
        res = simulate_offline(_cfg(radar_drop_rate=1.0, vision_drop_rate=1.0))
        radar = [m for m in res["messages"] if m["topic"] == "usv/RadarObservation"]
        vision = [m for m in res["messages"] if m["topic"] == "usv/VisionObservation"]
        self.assertEqual(len(radar), 0)
        self.assertEqual(len(vision), 0)


class NoNoiseTest(unittest.TestCase):
    def test_no_noise_matches_truth(self):
        res = simulate_offline(_cfg(radar_noise_m=0.0, vision_noise_m=0.0,
                                    radar_drop_rate=0.0, vision_drop_rate=0.0))
        truth_by_ts = {}
        for t in res["truth"]:
            truth_by_ts.setdefault(t["timestampUtc"], []).append(t)
        for m in res["messages"]:
            if m["topic"] not in ("usv/RadarObservation", "usv/VisionObservation"):
                continue
            p = m["payload"]
            self.assertEqual(p["qualityFlags"], [])
            matched = [t for t in truth_by_ts[p["timestampUtc"]]
                       if abs(t["longitude"] - p["longitude"]) < 1e-9
                       and abs(t["latitude"] - p["latitude"]) < 1e-9]
            self.assertEqual(len(matched), 1)


class AisOffTest(unittest.TestCase):
    def test_ais_off_target_no_ais_but_in_sensors(self):
        res = simulate_offline(_cfg())
        ais = [m for m in res["messages"] if m["topic"] == "usv/AisMessage"]
        for m in ais:
            self.assertNotEqual(m["payload"]["MMSI"], "900002222")
        # 真值中仍包含该目标，且 aisEnabled=False
        off_truth = [t for t in res["truth"] if t["targetId"] == "900002222"]
        self.assertGreater(len(off_truth), 0)
        for t in off_truth:
            self.assertFalse(t["aisEnabled"])
        # 雷达/视觉仍观测到该目标（按时间+位置匹配）
        radar = [m for m in res["messages"] if m["topic"] == "usv/RadarObservation"]
        self.assertGreater(len(radar), 0)


class JumpDropoutNotPolluteTruthTest(unittest.TestCase):
    def test_jump_does_not_affect_truth_or_sensors(self):
        # jump 仅影响 AIS 报文纬度，不污染真值与传感器观测。
        jumps = (("900001222", 10.0, 0.01),)
        res = simulate_offline(_cfg(jumps=jumps, radar_noise_m=0.0, vision_noise_m=0.0,
                                    radar_drop_rate=0.0, vision_drop_rate=0.0))
        # 真值应与无 jump 一致
        res2 = simulate_offline(_cfg(jumps=(), radar_noise_m=0.0, vision_noise_m=0.0,
                                     radar_drop_rate=0.0, vision_drop_rate=0.0))
        self.assertEqual(res["truth"], res2["truth"])
        self.assertEqual(
            [m for m in res["messages"] if m["topic"] != "usv/AisMessage"],
            [m for m in res2["messages"] if m["topic"] != "usv/AisMessage"],
        )

    def test_dropout_does_not_affect_truth_or_sensors(self):
        dropouts = (("900001222", 5.0, 20.0),)
        res = simulate_offline(_cfg(dropouts=dropouts, radar_noise_m=0.0, vision_noise_m=0.0,
                                    radar_drop_rate=0.0, vision_drop_rate=0.0))
        res2 = simulate_offline(_cfg(dropouts=(), radar_noise_m=0.0, vision_noise_m=0.0,
                                     radar_drop_rate=0.0, vision_drop_rate=0.0))
        self.assertEqual(res["truth"], res2["truth"])
        self.assertEqual(
            [m for m in res["messages"] if m["topic"] != "usv/AisMessage"],
            [m for m in res2["messages"] if m["topic"] != "usv/AisMessage"],
        )
        # AIS 在 dropout 窗口内确实缺失
        ais = [m for m in res["messages"] if m["topic"] == "usv/AisMessage"
               and m["payload"]["MMSI"] == "900001222"]
        ais2 = [m for m in res2["messages"] if m["topic"] == "usv/AisMessage"
                and m["payload"]["MMSI"] == "900001222"]
        self.assertLess(len(ais), len(ais2))


class TimeAlignmentTest(unittest.TestCase):
    def test_timestamps_align_with_epoch(self):
        cfg = _cfg()
        res = simulate_offline(cfg)
        expected_ts = [iso_z(cfg.epoch + timedelta(seconds=i))
                       for i in range(cfg.ticks)]
        got_ts = sorted({t["timestampUtc"] for t in res["truth"]})
        self.assertEqual(got_ts, expected_ts)
        for m in res["messages"]:
            if m["topic"] in ("usv/RadarObservation", "usv/VisionObservation"):
                self.assertIn(m["payload"]["timestampUtc"], expected_ts)


class IllegalParamTest(unittest.TestCase):
    def test_negative_noise_rejected(self):
        with self.assertRaises(ValueError):
            SimulationConfig(sensors=("radar",), radar_noise_m=-1.0)

    def test_drop_rate_out_of_range_rejected(self):
        with self.assertRaises(ValueError):
            SimulationConfig(sensors=("radar",), radar_drop_rate=1.5)
        with self.assertRaises(ValueError):
            SimulationConfig(sensors=("radar",), vision_drop_rate=-0.1)

    def test_bad_sensor_rejected(self):
        with self.assertRaises(ValueError):
            SimulationConfig(sensors=("lidar",))

    def test_non_finite_rejected(self):
        with self.assertRaises(ValueError):
            SimulationConfig(sensors=("radar",), radar_noise_m=float("nan"))


class OutputDirTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_refuse_overwrite_existing_dir(self):
        d = self.tmp / "out"
        d.mkdir()
        with self.assertRaises(FileExistsError):
            simulate_offline(_cfg(output_dir=d))

    def test_writes_files_and_manifest(self):
        d = self.tmp / "out"
        res = simulate_offline(_cfg(output_dir=d))
        self.assertTrue((d / "truth.jsonl").exists())
        self.assertTrue((d / "messages.jsonl").exists())
        self.assertTrue((d / "manifest.json").exists())
        manifest = json.loads((d / "manifest.json").read_text(encoding="utf-8"))
        self.assertIn("truthSha256", manifest)
        self.assertIn("messagesSha256", manifest)
        self.assertIn("params", manifest)
        # 文件内容与返回一致
        truth_lines = [json.loads(l) for l in (d / "truth.jsonl").read_text().splitlines()]
        self.assertEqual(truth_lines, res["truth"])


class SensorContractTest(unittest.TestCase):
    def test_six_fields_no_identity(self):
        res = simulate_offline(_cfg())
        for m in res["messages"]:
            if m["topic"] in ("usv/RadarObservation", "usv/VisionObservation"):
                p = m["payload"]
                self.assertEqual(set(p.keys()),
                                 {"sourceType", "timestampUtc", "latitude",
                                  "longitude", "sourceConfidence", "qualityFlags"})
                self.assertNotIn("mmsi", {k.lower() for k in p.keys()})
                self.assertNotIn("targetid", {k.lower() for k in p.keys()})
                self.assertTrue(math.isfinite(p["latitude"]))
                self.assertTrue(math.isfinite(p["longitude"]))
                self.assertTrue(0.0 <= p["sourceConfidence"] <= 1.0)
                if m["topic"] == "usv/RadarObservation":
                    self.assertEqual(p["sourceType"], "RADAR")
                else:
                    self.assertEqual(p["sourceType"], "VISION")


class MatchingTest(unittest.TestCase):
    def test_greedy_counterexample(self):
        pairs, total = match_rectangular([[1, 1.5], [2, 10]], 5)
        self.assertEqual(set(pairs), {(0, 1), (1, 0)})
        self.assertEqual(total, 3.5)
        # 另核对地理坐标输入的匹配数量。
        # T0=(0,0), T1=(0,3); O0 接近 T0 但也在 T1 门限内; O1 仅在 T0 门限内。
        # 距离：O0-T0=1, O0-T1=2, O1-T0=1.5, O1-T1=10(超门限)。
        # 贪心先选 O0-T0(1) → O1 无法匹配 → 1 个。
        # 最优选 O0-T1(2), O1-T0(1.5) → 2 个。
        truth = [
            {"timestampUtc": "2026-11-26T04:00:00.000Z", "targetId": "A",
             "longitude": 0.0, "latitude": 0.0, "aisEnabled": True},
            {"timestampUtc": "2026-11-26T04:00:00.000Z", "targetId": "B",
             "longitude": 0.0, "latitude": 3.0 / 111320.0, "aisEnabled": True},
        ]
        messages = [
            {"topic": "usv/RadarObservation", "payload": {
                "sourceType": "RADAR", "timestampUtc": "2026-11-26T04:00:00.000Z",
                "longitude": 1.0 / 111320.0, "latitude": 0.0,
                "sourceConfidence": 0.85, "qualityFlags": ["POSITION_NOISE"]}},
            {"topic": "usv/RadarObservation", "payload": {
                "sourceType": "RADAR", "timestampUtc": "2026-11-26T04:00:00.000Z",
                "longitude": 1.5 / 111320.0, "latitude": 0.0,
                "sourceConfidence": 0.85, "qualityFlags": ["POSITION_NOISE"]}},
        ]
        report = verify_observations(truth, messages, max_distance_m=5.0)
        radar = report["RADAR"]
        self.assertEqual(radar["matched"], 2)
        self.assertEqual(radar["missing"], 0)
        self.assertEqual(radar["unmatched"], 0)

    def test_rectangular_matching_basic(self):
        # 3 真值 2 观测，门限内，应匹配 2 个。
        truth = [
            {"timestampUtc": "t", "targetId": "A", "longitude": 0.0, "latitude": 0.0, "aisEnabled": True},
            {"timestampUtc": "t", "targetId": "B", "longitude": 0.0, "latitude": 0.001, "aisEnabled": True},
            {"timestampUtc": "t", "targetId": "C", "longitude": 0.0, "latitude": 0.002, "aisEnabled": True},
        ]
        obs = [
            (0.0, 0.0),
            (0.0, 0.001),
        ]
        cost = [[haversine_m((t["longitude"], t["latitude"]), o) for o in obs] for t in truth]
        pairs, total = match_rectangular(cost, 200.0)
        self.assertEqual(len(pairs), 2)


class VerifyReportTest(unittest.TestCase):
    def test_report_counts_and_ais_off_acceptance(self):
        res = simulate_offline(_cfg())
        report = verify_observations(res["truth"], res["messages"], max_distance_m=50.0)
        for src in ("RADAR", "VISION"):
            r = report[src]
            self.assertEqual(r["expected"], r["matched"] + r["missing"])
            self.assertEqual(r["observed"], r["matched"] + r["unmatched"])
            self.assertGreaterEqual(r["matched"], 0)
        # AIS 关闭目标在 messages 中无 AIS 报文
        ais = [m for m in res["messages"] if m["topic"] == "usv/AisMessage"]
        for m in ais:
            self.assertNotEqual(m["payload"]["MMSI"], "900002222")
        self.assertEqual(report["aisOffAisCount"], 0)

    def test_illegal_message_rejected(self):
        truth = []
        messages = [{"topic": "usv/RadarObservation", "payload": {
            "sourceType": "VISION",  # 与 topic 不符
            "timestampUtc": "2026-11-26T04:00:00.000Z",
            "latitude": 30.0, "longitude": 114.0,
            "sourceConfidence": 0.85, "qualityFlags": []}}]
        with self.assertRaises(ValueError):
            verify_observations(truth, messages, max_distance_m=50.0)


# ---------------------------------------------------------------------------
# 第二轮回归：在线/离线一致性、jump 单次、参数校验、输出目录预留、发布 rc 检查
# ---------------------------------------------------------------------------

class _StubInfo:
    def __init__(self, rc=0):
        self.rc = rc

    def wait_for_publish(self, timeout=None):
        return None

    def is_published(self):
        return self.rc == 0


class _StubClient:
    def __init__(self, rc=0):
        self.rc = rc
        self.published = []

    def publish(self, topic, payload):
        self.published.append((topic, payload))
        return _StubInfo(self.rc)

    def loop_start(self):
        pass

    def loop_stop(self):
        pass

    def disconnect(self):
        pass


class OnlineOfflineConsistencyTest(unittest.TestCase):
    def test_epoch_online_matches_offline(self):
        cfg = _cfg(ticks=10)
        off = simulate_offline(cfg)
        pub = WuhanPublisher(MqttConfig(), cfg.route_path, cfg.start_at,
                             cfg.dropouts, cfg.jumps, cfg)
        pub.client = _StubClient(rc=0)
        pub.before_loop()
        for _ in range(cfg.ticks):
            pub.publish_tick()
        self.assertEqual(pub._messages, off["messages"])
        self.assertEqual(pub._truth, off["truth"])


class JumpOneShotTest(unittest.TestCase):
    def test_jump_applied_once_only(self):
        jumps = (("900001222", 5.0, 0.01),)
        cfg = _cfg(jumps=jumps, ticks=20, sensors=("ais",))
        res = simulate_offline(cfg)
        res2 = simulate_offline(_cfg(jumps=(), ticks=20, sensors=("ais",)))
        ais = [m for m in res["messages"] if m["topic"] == "usv/AisMessage"
               and m["payload"]["MMSI"] == "900001222"]
        ais2 = [m for m in res2["messages"] if m["topic"] == "usv/AisMessage"
                and m["payload"]["MMSI"] == "900001222"]
        diffs = [a["payload"]["LAT"] - b["payload"]["LAT"] for a, b in zip(ais, ais2)]
        jumped = sum(1 for d in diffs if abs(d - 0.01) < 1e-9)
        normal = sum(1 for d in diffs if abs(d) < 1e-9)
        self.assertEqual(jumped, 1)
        self.assertEqual(normal, len(diffs) - 1)


class StrictValidationTest(unittest.TestCase):
    def test_unknown_ais_off_rejected(self):
        with self.assertRaises(ValueError):
            simulate_offline(_cfg(ais_off=("999999999",), ticks=5))

    def test_non_finite_injection_rejected(self):
        with self.assertRaises(ValueError):
            parse_injection(["900001222:nan:5"], "START:END")
        with self.assertRaises(ValueError):
            parse_injection(["900001222:inf:5"], "START:END")

    def test_dropout_window_reverse_rejected(self):
        with self.assertRaises(ValueError):
            simulate_offline(_cfg(dropouts=(("900001222", 20.0, 5.0),), ticks=5,
                                  sensors=("ais",)))

    def test_empty_sensors_rejected(self):
        with self.assertRaises(ValueError):
            _parse_sensors("")

    def test_duplicate_sensors_rejected(self):
        with self.assertRaises(ValueError):
            _parse_sensors("ais,ais")


class OutputDirReservationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_existing_dir_rejected_before_sim(self):
        d = self.tmp / "out"
        d.mkdir()
        (d / "stale.txt").write_text("x", encoding="utf-8")
        with self.assertRaises(FileExistsError):
            simulate_offline(_cfg(output_dir=d, ticks=5))
        # 目录内容未被改动
        self.assertEqual((d / "stale.txt").read_text(encoding="utf-8"), "x")
        self.assertFalse((d / "truth.jsonl").exists())

    def test_manifest_hash_matches_file_bytes(self):
        d = self.tmp / "out"
        simulate_offline(_cfg(output_dir=d, ticks=5))
        manifest = json.loads((d / "manifest.json").read_text(encoding="utf-8"))
        truth_bytes = (d / "truth.jsonl").read_bytes()
        msg_bytes = (d / "messages.jsonl").read_bytes()
        self.assertEqual(manifest["truthSha256"],
                         hashlib.sha256(truth_bytes).hexdigest())
        self.assertEqual(manifest["messagesSha256"],
                         hashlib.sha256(msg_bytes).hexdigest())

    def test_manifest_records_mode_and_route_hash(self):
        d = self.tmp / "out"
        cfg = _cfg(output_dir=d, ticks=5)
        simulate_offline(cfg)
        manifest = json.loads((d / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(manifest["mode"], "offline")
        self.assertIn("routeSha256", manifest)
        route_bytes = Path(cfg.route_path).read_bytes()
        self.assertEqual(manifest["routeSha256"],
                         hashlib.sha256(route_bytes).hexdigest())


class PublishRcCheckTest(unittest.TestCase):
    def test_failed_publish_not_recorded(self):
        cfg = _cfg(ticks=3)
        pub = WuhanPublisher(MqttConfig(), cfg.route_path, cfg.start_at,
                             cfg.dropouts, cfg.jumps, cfg)
        pub.client = _StubClient(rc=1)
        pub.before_loop()
        with self.assertRaises(RuntimeError):
            pub.publish_tick()
        self.assertEqual(pub._messages, [])
        self.assertEqual(len(pub._truth), 3)

    def test_successful_publish_recorded(self):
        cfg = _cfg(ticks=3)
        pub = WuhanPublisher(MqttConfig(), cfg.route_path, cfg.start_at,
                             cfg.dropouts, cfg.jumps, cfg)
        stub = _StubClient(rc=0)
        pub.client = stub
        pub.before_loop()
        for _ in range(cfg.ticks):
            pub.publish_tick()
        self.assertGreater(len(pub._messages), 0)
        self.assertGreater(len(stub.published), 0)




class AcceptanceRegressionTest(unittest.TestCase):
    def test_valid_scene_passes(self):
        r = simulate_offline(_cfg(ticks=60))
        report = verify_observations(r['truth'], r['messages'], 100)
        self.assertTrue(report['pass'])
        self.assertTrue(report['aisOffTargets']['900002222']['pass'])

    def test_no_truth_or_no_messages_never_pass(self):
        self.assertFalse(verify_observations([], [], 50)['pass'])
        r = simulate_offline(_cfg(ticks=2))
        report = verify_observations(r['truth'], [], 50)
        self.assertFalse(report['pass'])
        self.assertIsNone(report['RADAR']['meanErrorM'])

    def test_missing_one_source_never_pass(self):
        r = simulate_offline(_cfg(ticks=3))
        messages = [m for m in r['messages'] if m['topic'] != 'usv/VisionObservation']
        self.assertFalse(verify_observations(r['truth'], messages, 100)['pass'])

    def test_unsubscribed_ais_cannot_prove_disabled(self):
        r = simulate_offline(_cfg(ticks=3))
        messages = [m for m in r['messages'] if m['topic'] != 'usv/AisMessage']
        self.assertFalse(verify_observations(r['truth'], messages, 100)['pass'])

    def test_parameter_change_does_not_affect_other_source(self):
        a = simulate_offline(_cfg(ticks=5))
        b = simulate_offline(_cfg(ticks=5, vision_noise_m=100, vision_drop_rate=0.9))
        c = simulate_offline(_cfg(ticks=5, radar_noise_m=100, radar_drop_rate=0.9))
        def source(r, topic):
            return [m for m in r['messages'] if m['topic'] == topic]
        self.assertEqual(source(a, 'usv/RadarObservation'), source(b, 'usv/RadarObservation'))
        self.assertEqual(source(a, 'usv/VisionObservation'), source(c, 'usv/VisionObservation'))

    def test_bool_numbers_bad_time_flags_and_truth_rejected(self):
        import copy
        r = simulate_offline(_cfg(ticks=2))
        sensor_index = next(i for i,m in enumerate(r['messages']) if m['topic'] == 'usv/RadarObservation')
        for key, value in [('latitude', True), ('longitude', float('inf')),
                           ('sourceConfidence', False), ('timestampUtc', 'garbageZ'),
                           ('qualityFlags', ['BAD-FLAG']), ('qualityFlags', ['A', 'A'])]:
            with self.subTest(key=key, value=value):
                messages = copy.deepcopy(r['messages'])
                messages[sensor_index]['payload'][key] = value
                with self.assertRaises(ValueError):
                    verify_observations(r['truth'], messages, 50)
        for rows in [[None], r['truth']+[r['truth'][0]],
                     [dict(r['truth'][0], latitude=True)],
                     [dict(r['truth'][0], targetId='')]]:
            with self.assertRaises(ValueError):
                verify_observations(rows, r['messages'], 50)

    def test_equivalent_time_and_unknown_tick(self):
        import copy
        r = simulate_offline(_cfg(ticks=3))
        changed = copy.deepcopy(r['messages'])
        sensor_index = next(i for i,m in enumerate(changed) if m['topic'] == 'usv/RadarObservation')
        stamp = changed[sensor_index]['payload']['timestampUtc']
        changed[sensor_index]['payload']['timestampUtc'] = stamp.replace('.000000Z', 'Z')
        original = verify_observations(r['truth'], r['messages'], 100)
        self.assertEqual(original, verify_observations(r['truth'], changed, 100))
        changed[sensor_index]['payload']['timestampUtc'] = '2027-01-01T00:00:00Z'
        self.assertFalse(verify_observations(r['truth'], changed, 100)['pass'])

    def test_invalid_gate_and_config_types(self):
        for gate in [0, -1, True, float('nan'), float('inf')]:
            with self.assertRaises(ValueError):
                verify_observations([], [], gate)
        for opts in [dict(ticks=True), dict(seed=True), dict(interval=True),
                     dict(sensors=()), dict(sensors=('radar', 'radar'))]:
            with self.assertRaises(ValueError):
                _cfg(**opts)

    def test_json_errors_include_path_and_line(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root)/'invalid.jsonl'
            for text in ['{}\n{"a":1,"a":2}', '{}\n{"a":NaN}', '{}\n[]']:
                path.write_text(text, encoding='utf-8')
                with self.assertRaisesRegex(ValueError, r'invalid.jsonl:2:'):
                    load_jsonl(path)
            path.write_text('{}\n{"topic":"usv/RadarObservation","payload":{}}', encoding='utf-8')
            # 先单独加载合法封装、但 schema 非法的一行，保留原始行号。
            row = load_jsonl(path)[1:]
            with self.assertRaisesRegex(ValueError, r'invalid.jsonl:2:'):
                verify_observations([], row, 50)

    def test_ais_leak_fails_acceptance(self):
        r = simulate_offline(_cfg(ticks=2))
        import copy
        leak = copy.deepcopy(next(m for m in r['messages'] if m['topic'] == 'usv/AisMessage'))
        leak['payload']['MMSI'] = '900002222'
        self.assertFalse(verify_observations(r['truth'], r['messages']+[leak], 100)['pass'])

    def test_minimum_total_distance_after_maximum_count(self):
        pairs, total = match_rectangular([[4, 1], [2, 4]], 5)
        self.assertEqual(set(pairs), {(0,1), (1,0)})
        self.assertEqual(total, 3)

    def test_timeout_not_recorded(self):
        cfg = _cfg(ticks=1)
        pub = WuhanPublisher(MqttConfig(), cfg.route_path, sim_cfg=cfg)
        class Info(_StubInfo):
            def is_published(self):
                return False
        class Client(_StubClient):
            def publish(self, topic, payload):
                return Info(0)
        pub.client = Client()
        pub.before_loop()
        with self.assertRaises(RuntimeError):
            pub.publish_tick()
        self.assertEqual(pub._messages, [])

    def test_custom_ais_topic(self):
        cfg = _cfg(ticks=1, ais_topic='local/Ais')
        r = simulate_offline(cfg)
        self.assertTrue(any(m['topic'] == 'local/Ais' for m in r['messages']))
        verify_observations(r['truth'], r['messages'], 100, ais_topic='local/Ais')


if __name__ == "__main__":
    unittest.main()