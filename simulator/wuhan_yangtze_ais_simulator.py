# -*- coding: utf-8 -*-
"""Single-pass Wuhan Yangtze demo; no endpoint teleport or model calls.

Geometry reference: trajectory-service/runtime/dataset/segments.parquet,
seg_id=8302: (114.293625,30.575418) -> (114.309620,30.599673).
The straight reach and riverward offsets were checked against Esri imagery.
These are synthetic demonstration routes, not navigational guidance.

The own-ship route is read from the backend planned-route file (v1.3 step-1), so the
simulator and the backend share one route source; its first leg is the original
head-on / overtaking leg and the following legs pass the Wuhan Second Bridge virtual buoys.

Run: simulator/venv/bin/python simulator/wuhan_yangtze_ais_simulator.py
Default: 1 Hz; stop 35 seconds after every vessel reaches its endpoint.
The head-on pair starts at 3 kn for 30 s, then accelerates over 90 s.
--start-at fast-forwards the timeline for a short demo, e.g. 180 reaches the head-on CPA
in about 2.5 minutes.

新增（雷达/视觉两路 MQTT 观测）：
- --sensors ais,radar,vision 选择输出源，默认 ais 保持向后兼容。
- --radar-noise-m / --vision-noise-m：每轴高斯位置噪声标准差（米）。
- --radar-drop-rate / --vision-drop-rate：伯努利丢帧概率 [0,1]。
- --seed：雷达/视觉各自独立固定 seed 的随机噪声与丢帧，保证可复现。
- --ais-off MMSI（可重复）：关闭该船的 AIS 报文，但其仍进入真值与雷达/视觉观测。
- --epoch ISO8601Z：明确 UTC 时区 + 固定 interval/tick 时间轴，确保可复现；保留 --start-at。
- --output-dir 新目录（拒绝覆盖）：写 truth.jsonl / messages.jsonl / manifest.json。
- --offline：有限 ticks 离线生成，不连接 MQTT。
每 tick 先采全部无噪声真值，再处理 AIS dropout/jump；传感器严格六字段，无身份/真值。
"""
import argparse
import hashlib
import json
import math
import random
import time
from dataclasses import dataclass, field, asdict
from datetime import datetime, timezone, timedelta
from pathlib import Path
from typing import Optional

from ais_simulator import KNOT_TO_MPS, bearing_deg, format_msg_time, haversine_m, interpolate
from mqtt_publisher_base import BaseMqttPublisher, MqttConfig

START = (114.293625, 30.575418)
END = (114.309620, 30.599673)
OWN_MMSI = '123456789'
DEFAULT_ROUTE = (Path(__file__).resolve().parent.parent / 'backend' / 'map-service' / 'src' / 'main'
                 / 'resources' / 'routes' / 'wuhan-demo-own.route.json')

# 传感器 topic 与来源类型（见 SIMULATOR_MESSAGE_FORMAT.md §8.1）
RADAR_TOPIC = 'usv/RadarObservation'
VISION_TOPIC = 'usv/VisionObservation'
AIS_TOPIC = 'usv/AisMessage'
RADAR_SOURCE_TYPE = 'RADAR'
VISION_SOURCE_TYPE = 'VISION'
# 演示用常量置信度（不来自实际误差，仅来源质量假设）
RADAR_CONFIDENCE = 0.85
VISION_CONFIDENCE = 0.70
# 米→度换算常数
_M_PER_DEG_LAT = 6371000.0 * math.pi / 180.0


@dataclass(frozen=True)
class Vessel:
    mmsi: str
    path: tuple[tuple[float, float], ...]
    cruise_kn: float
    initial_kn: float = 3.0

    @property
    def start(self):
        return self.path[0]

    @property
    def end(self):
        return self.path[-1]

    def sample(self, elapsed: float):
        """Return position, instantaneous SOG, COG for real elapsed seconds along the polyline."""
        elapsed = max(0.0, elapsed)
        ramp = min(max(elapsed - 30.0, 0.0), 90.0)
        acceleration = (self.cruise_kn - self.initial_kn) / 90.0
        distance = KNOT_TO_MPS * (
            self.initial_kn * min(elapsed, 120.0)
            + acceleration * ramp * ramp / 2.0
            + self.cruise_kn * max(elapsed - 120.0, 0.0)
        )
        speed = self.initial_kn + acceleration * ramp
        for a, b in zip(self.path, self.path[1:]):
            length = haversine_m(a, b)
            if distance < length:
                return interpolate(a, b, distance / length), speed, bearing_deg(a, b)
            distance -= length
        return self.end, 0.0, bearing_deg(self.path[-2], self.path[-1])


def offset(point, meters):
    """Positive is left of the downstream reference line; negative is riverward."""
    mid_lat = math.radians((START[1] + END[1]) / 2)
    lon_scale = 6371000 * math.pi / 180 * math.cos(mid_lat)
    lat_scale = 6371000 * math.pi / 180
    dx = (END[0] - START[0]) * lon_scale
    dy = (END[1] - START[1]) * lat_scale
    length = math.hypot(dx, dy)
    return (point[0] - dy/length*meters/lon_scale,
            point[1] + dx/length*meters/lat_scale)


def load_route_path(route_path=DEFAULT_ROUTE):
    """Waypoints of the backend planned-route file as (lon, lat) tuples."""
    route = json.loads(Path(route_path).read_text(encoding='utf-8'))
    return tuple((w['lon'], w['lat']) for w in route['waypoints'])


def build_routes(route_path=DEFAULT_ROUTE):
    slow_start = interpolate(START, END, 1000/haversine_m(START, END))
    return [
        Vessel(OWN_MMSI, load_route_path(route_path), 11.5),
        Vessel('900001222', (offset(END, -220), offset(START, -220)), 11.0),
        Vessel('900002222', (offset(slow_start, -480), offset(END, -480)), 7.0, 7.0),
    ]


# ---------------------------------------------------------------------------
# 时间与噪声工具
# ---------------------------------------------------------------------------

def parse_epoch(text: str) -> datetime:
    """解析带明确时区的 ISO 时间，并转换为 UTC。"""
    dt = datetime.fromisoformat(text.strip().replace('Z', '+00:00'))
    if dt.tzinfo is None or dt.utcoffset() is None:
        raise ValueError('epoch 必须带明确时区')
    return dt.astimezone(timezone.utc)


def iso_z(dt: datetime) -> str:
    if dt.tzinfo is None or dt.utcoffset() is None:
        raise ValueError('时间必须带时区')
    return dt.astimezone(timezone.utc).isoformat(timespec='microseconds').replace('+00:00', 'Z')


def _finite(value, name, min_value=None, max_value=None):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f'{name} 必须为数值')
    if not math.isfinite(value):
        raise ValueError(f'{name} 必须为有限数值')
    if min_value is not None and value < min_value:
        raise ValueError(f'{name} 不能小于 {min_value}')
    if max_value is not None and value > max_value:
        raise ValueError(f'{name} 不能大于 {max_value}')
    return float(value)


def add_position_noise(lon, lat, noise_m, rng):
    """用本地东/北坐标施加每轴独立的米制高斯噪声。"""
    if noise_m == 0:
        return lon, lat
    dx, dy = rng.gauss(0, noise_m), rng.gauss(0, noise_m)
    noisy_lon = lon + dx / (_M_PER_DEG_LAT * math.cos(math.radians(lat)))
    noisy_lat = lat + dy / _M_PER_DEG_LAT
    if not (-180 <= noisy_lon <= 180 and -90 <= noisy_lat <= 90):
        raise ValueError('噪声导致观测坐标超出范围，请减小标准差')
    return noisy_lon, noisy_lat


_VALID_SENSORS = ('ais', 'radar', 'vision')


@dataclass
class SimulationConfig:
    sensors: tuple = ('ais',)
    radar_noise_m: float = 0.0
    vision_noise_m: float = 0.0
    radar_drop_rate: float = 0.0
    vision_drop_rate: float = 0.0
    seed: int = 0
    ais_off: tuple = ()
    epoch: Optional[datetime] = None
    interval: float = 1.0
    ticks: int = 60
    start_at: float = 0.0
    dropouts: tuple = ()
    jumps: tuple = ()
    route_path: str = str(DEFAULT_ROUTE)
    output_dir: Optional[Path] = None
    ais_topic: str = AIS_TOPIC

    def __post_init__(self):
        if isinstance(self.sensors, str):
            raise ValueError('sensors 必须为来源序列')
        self.sensors = tuple(self.sensors)
        if not self.sensors or len(set(self.sensors)) != len(self.sensors):
            raise ValueError('来源不能为空或重复')
        if any(s not in _VALID_SENSORS for s in self.sensors):
            raise ValueError('未知来源')
        for name in ('radar_noise_m', 'vision_noise_m', 'start_at'):
            setattr(self, name, _finite(getattr(self, name), name, 0))
        for name in ('radar_drop_rate', 'vision_drop_rate'):
            setattr(self, name, _finite(getattr(self, name), name, 0, 1))
        self.interval = _finite(self.interval, 'interval', 0.000001)
        if type(self.ticks) is not int or self.ticks < 0:
            raise ValueError('ticks 必须为非负整数')
        if type(self.seed) is not int:
            raise ValueError('seed 必须为整数')
        if self.epoch is not None:
            if not isinstance(self.epoch, datetime) or self.epoch.tzinfo is None:
                raise ValueError('epoch 必须为带时区的时间')
            self.epoch = self.epoch.astimezone(timezone.utc)
        self.ais_off = tuple(self.ais_off)
        if any(not isinstance(m, str) or not m.isascii() or not m.isdigit() for m in self.ais_off):
            raise ValueError('AIS 关闭目标必须是数字字符串')
        for name in ('dropouts', 'jumps'):
            cleaned = []
            for spec in getattr(self, name):
                if len(spec) != 3 or not isinstance(spec[0], str):
                    raise ValueError(f'{name} 格式错误')
                m, a, b = spec
                a = _finite(a, name + '时间', 0)
                b = _finite(b, name + '结束时间或偏移')
                if name == 'dropouts' and b < a:
                    raise ValueError('断报结束时间不能早于开始时间')
                cleaned.append((m, a, b))
            setattr(self, name, tuple(cleaned))
        if not isinstance(self.ais_topic, str) or not self.ais_topic or any(c in self.ais_topic for c in '+#\x00'):
            raise ValueError('AIS topic 必须为有效的单一 topic')
        if self.ais_topic in (RADAR_TOPIC, VISION_TOPIC):
            raise ValueError('AIS topic 不能与观测 topic 相同')
        if self.output_dir is not None:
            self.output_dir = Path(self.output_dir)


def _validate_targets(cfg, vessels):
    known = {v.mmsi for v in vessels}
    requested = set(cfg.ais_off) | {s[0] for s in cfg.dropouts + cfg.jumps}
    if requested - known:
        raise ValueError(f'未知目标: {sorted(requested - known)}')


def make_rngs(seed):
    """噪声与丢帧分离，各来源参数互不影响。"""
    return {name: random.Random(f'{seed}:{name}') for name in
            ('radar_noise', 'radar_drop', 'vision_noise', 'vision_drop')}


def compute_tick(vessels, elapsed, cfg, rngs, tick_dt_utc, tick_dt_local, jump_done=None):
    if jump_done is None:
        jump_done = set()
    ts = iso_z(tick_dt_utc)
    samples = [(v, *v.sample(elapsed)) for v in vessels]
    truth = [{'timestampUtc': ts, 'targetId': v.mmsi,
              'longitude': pos[0], 'latitude': pos[1],
              'aisEnabled': v.mmsi not in cfg.ais_off}
             for v, pos, speed, heading in samples]
    messages = []
    for v, (lon, lat), speed, heading in samples:
        if 'ais' not in cfg.sensors or v.mmsi in cfg.ais_off:
            continue
        if any(m == v.mmsi and a <= elapsed <= b for m, a, b in cfg.dropouts):
            continue
        ais_lat = lat
        for m, t, delta in cfg.jumps:
            if m == v.mmsi and elapsed >= t and (m, t) not in jump_done:
                ais_lat += delta
                jump_done.add((m, t))
        if not -90 <= ais_lat <= 90:
            raise ValueError('AIS 跳变导致纬度超界')
        messages.append({'topic': cfg.ais_topic, 'payload': {
            'MSGTIME': format_msg_time(tick_dt_local), 'MMSI': v.mmsi,
            'LAT': round(ais_lat, 6), 'LON': round(lon, 6),
            'SOG': round(speed, 2), 'COG': round(heading, 1), 'HEADING': round(heading, 1)}})
    for v, (lon, lat), speed, heading in samples:
        for source, topic, confidence in (('radar', RADAR_TOPIC, RADAR_CONFIDENCE),
                                           ('vision', VISION_TOPIC, VISION_CONFIDENCE)):
            if source not in cfg.sensors:
                continue
            if rngs[source + '_drop'].random() < getattr(cfg, source + '_drop_rate'):
                continue
            noise = getattr(cfg, source + '_noise_m')
            x, y = add_position_noise(lon, lat, noise, rngs[source + '_noise'])
            messages.append({'topic': topic, 'payload': {
                'sourceType': source.upper(), 'timestampUtc': ts,
                'latitude': y, 'longitude': x, 'sourceConfidence': confidence,
                'qualityFlags': ['POSITION_NOISE'] if noise else []}})
    return truth, messages


def _reserve_output(output_dir):
    if output_dir is not None:
        Path(output_dir).mkdir(parents=True, exist_ok=False)


def _jsonl_bytes(rows):
    return ''.join(json.dumps(r, ensure_ascii=False, allow_nan=False) + '\n' for r in rows).encode('utf-8')


def _build_manifest(cfg, truth, messages, mode='offline', actual_epoch=None, status='complete'):
    params = {name: getattr(cfg, name) for name in (
        'sensors', 'radar_noise_m', 'vision_noise_m', 'radar_drop_rate',
        'vision_drop_rate', 'seed', 'ais_off', 'interval', 'ticks', 'start_at',
        'dropouts', 'jumps', 'ais_topic')}
    params['epoch'] = iso_z(actual_epoch or cfg.epoch) if (actual_epoch or cfg.epoch) else None
    params['fixedTimeline'] = cfg.epoch is not None or mode == 'offline'
    return {'mode': mode, 'status': status, 'params': params,
            'evidenceType': 'offline_generation' if mode == 'offline' else 'publisher_send_log',
            'routeSha256': hashlib.sha256(Path(cfg.route_path).read_bytes()).hexdigest(),
            'truthCount': len(truth), 'messagesCount': len(messages),
            'truthSha256': hashlib.sha256(_jsonl_bytes(truth)).hexdigest(),
            'messagesSha256': hashlib.sha256(_jsonl_bytes(messages)).hexdigest()}


def _write_output(output_dir, truth, messages, manifest):
    """目录由运行开始前独占创建。固定写 LF，使哈希跨平台一致。"""
    if output_dir is None:
        return
    (Path(output_dir) / 'truth.jsonl').write_bytes(_jsonl_bytes(truth))
    (Path(output_dir) / 'messages.jsonl').write_bytes(_jsonl_bytes(messages))
    (Path(output_dir) / 'manifest.json').write_text(
        json.dumps(manifest, ensure_ascii=False, allow_nan=False, indent=2), encoding='utf-8')


def simulate_offline(cfg):
    if cfg.ticks <= 0:
        raise ValueError('离线运行必须指定正数 ticks')
    vessels = build_routes(cfg.route_path)
    _validate_targets(cfg, vessels)
    _reserve_output(cfg.output_dir)
    epoch = cfg.epoch or datetime.now(timezone.utc)
    rngs, done = make_rngs(cfg.seed), set()
    truth, messages = [], []
    for i in range(cfg.ticks):
        elapsed = cfg.start_at + i * cfg.interval
        dt = epoch + timedelta(seconds=elapsed)
        t, m = compute_tick(vessels, elapsed, cfg, rngs, dt, dt.astimezone().replace(tzinfo=None), done)
        truth.extend(t)
        messages.extend(m)
    manifest = _build_manifest(cfg, truth, messages, actual_epoch=epoch)
    _write_output(cfg.output_dir, truth, messages, manifest)
    return {'truth': truth, 'messages': messages, 'manifest': manifest}


class WuhanPublisher(BaseMqttPublisher):
    """同一 tick 先保存真值，再分别发布 AIS 与两路无身份观测。"""
    def __init__(self, config, route_path=DEFAULT_ROUTE, start_at=0.0,
                 dropouts=(), jumps=(), sim_cfg=None):
        super().__init__(config)
        self.routes = build_routes(route_path)
        self.sim_cfg = sim_cfg or SimulationConfig(route_path=str(route_path), start_at=start_at,
                                                  dropouts=tuple(dropouts), jumps=tuple(jumps),
                                                  ticks=0, ais_topic=config.topic)
        self.sim_cfg.ais_topic = config.topic
        _validate_targets(self.sim_cfg, self.routes)
        _reserve_output(self.sim_cfg.output_dir)
        self.start_at = self.sim_cfg.start_at
        self.started = None
        self.finished_at = None
        self.jump_done = set()
        self.rngs = make_rngs(self.sim_cfg.seed)
        self._truth, self._messages = [], []
        self._tick_index = 0
        self._actual_epoch = None
        self._status = 'complete'

    def before_loop(self):
        self.started = time.monotonic() - self.start_at
        self._actual_epoch = self.sim_cfg.epoch or datetime.now(timezone.utc)
        print('Wuhan Yangtze: AIS/RADAR/VISION synthetic observations', flush=True)

    def publish_tick(self):
        cfg = self.sim_cfg
        elapsed = (cfg.start_at + self._tick_index * cfg.interval if cfg.epoch is not None
                   else time.monotonic() - self.started)
        dt = (cfg.epoch + timedelta(seconds=elapsed) if cfg.epoch is not None
              else datetime.now(timezone.utc))
        truth, messages = compute_tick(self.routes, elapsed, cfg, self.rngs, dt,
                                       dt.astimezone().replace(tzinfo=None), self.jump_done)
        self._truth.extend(truth)
        self._tick_index += 1
        for message in messages:
            info = self.client.publish(message['topic'], json.dumps(message['payload'], allow_nan=False))
            if info.rc != 0:
                self._status = 'failed'
                raise RuntimeError(f'MQTT 发布失败，rc={info.rc}')
            info.wait_for_publish(timeout=5)
            if not info.is_published():
                self._status = 'failed'
                raise RuntimeError('MQTT 发送超时')
            self._messages.append(message)
            print(json.dumps(message, ensure_ascii=False), flush=True)
        stopped = all(v.sample(elapsed)[1] == 0 for v in self.routes)
        if any(a <= elapsed <= b for _, a, b in cfg.dropouts):
            stopped = False
        if stopped:
            if self.finished_at is None:
                self.finished_at = elapsed
            if elapsed - self.finished_at >= 35:
                self._running = False

    def after_loop(self):
        cfg = self.sim_cfg
        manifest = _build_manifest(cfg, self._truth, self._messages, 'mqtt', self._actual_epoch, self._status)
        _write_output(cfg.output_dir, self._truth, self._messages, manifest)

    def run(self, interval, ticks=0):
        self._running = True
        connected = False
        try:
            self.connect()
            connected = True
            self.install_signal_handlers()
            self.before_loop()
            n = 0
            while self._running and (ticks == 0 or n < ticks):
                self.publish_tick()
                n += 1
                if self._running and (ticks == 0 or n < ticks):
                    time.sleep(interval)
        except BaseException:
            self._status = 'failed'
            raise
        finally:
            if self._running is False and ticks and self._tick_index < ticks:
                self._status = 'interrupted'
            try:
                self.after_loop()
            finally:
                if connected:
                    self.disconnect()


def parse_injection(specs, fields):
    result = []
    for spec in specs:
        parts = spec.split(':')
        if len(parts) != 3 or not parts[0].isascii() or not parts[0].isdigit():
            raise ValueError(f'注入格式应为 MMSI:{fields}')
        a, b = float(parts[1]), float(parts[2])
        _finite(a, '注入时间', 0)
        _finite(b, '注入结束时间或偏移')
        result.append((parts[0], a, b))
    return result


def _parse_sensors(text):
    parts = tuple(p.strip().lower() for p in text.split(','))
    if not parts or any(p not in _VALID_SENSORS for p in parts) or len(set(parts)) != len(parts):
        raise ValueError('来源必须为非空且不重复的 ais/radar/vision')
    return parts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='localhost')
    parser.add_argument('--port', type=int, default=1883)
    parser.add_argument('--topic', default=AIS_TOPIC)
    parser.add_argument('--interval', type=float, default=1.0)
    parser.add_argument('--ticks', type=int, default=0)
    parser.add_argument('--username', default=None)
    parser.add_argument('--password', default=None)
    parser.add_argument('--route', default=str(DEFAULT_ROUTE))
    parser.add_argument('--start-at', type=float, default=0.0)
    parser.add_argument('--dropout', action='append', default=[])
    parser.add_argument('--jump', action='append', default=[])
    parser.add_argument('--sensors', default='ais')
    for name in ('radar-noise-m', 'vision-noise-m', 'radar-drop-rate', 'vision-drop-rate'):
        parser.add_argument('--' + name, type=float, default=0.0)
    parser.add_argument('--seed', type=int, default=0)
    parser.add_argument('--ais-off', action='append', default=[])
    parser.add_argument('--epoch')
    parser.add_argument('--output-dir')
    parser.add_argument('--offline', action='store_true')
    args = parser.parse_args()
    try:
        cfg = SimulationConfig(
            sensors=_parse_sensors(args.sensors), radar_noise_m=args.radar_noise_m,
            vision_noise_m=args.vision_noise_m, radar_drop_rate=args.radar_drop_rate,
            vision_drop_rate=args.vision_drop_rate, seed=args.seed, ais_off=tuple(args.ais_off),
            epoch=parse_epoch(args.epoch) if args.epoch else None, interval=args.interval,
            ticks=args.ticks, start_at=args.start_at, dropouts=tuple(parse_injection(args.dropout, 'START:END')),
            jumps=tuple(parse_injection(args.jump, 'TIME:LAT')), route_path=args.route,
            output_dir=Path(args.output_dir) if args.output_dir else None, ais_topic=args.topic)
        if not 1 <= args.port <= 65535:
            raise ValueError('port 超出范围')
        if args.offline:
            if cfg.output_dir is None:
                raise ValueError('离线运行需要 --output-dir')
            result = simulate_offline(cfg)
            print(f'offline truth={len(result["truth"])} messages={len(result["messages"])}')
        else:
            config = MqttConfig(host=args.host, port=args.port, topic=args.topic,
                                username=args.username, password=args.password)
            WuhanPublisher(config, args.route, args.start_at, cfg.dropouts, cfg.jumps, cfg).run(
                interval=args.interval, ticks=args.ticks)
    except (ValueError, OSError, RuntimeError) as exc:
        parser.exit(2, f'错误: {exc}\n')


if __name__ == '__main__':
    main()
