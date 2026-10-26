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
"""
import argparse
import json
import math
import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

from ais_simulator import KNOT_TO_MPS, bearing_deg, format_msg_time, haversine_m, interpolate
from mqtt_publisher_base import BaseMqttPublisher, MqttConfig

START = (114.293625, 30.575418)
END = (114.309620, 30.599673)
OWN_MMSI = '123456789'
DEFAULT_ROUTE = (Path(__file__).resolve().parent.parent / 'backend' / 'map-service' / 'src' / 'main'
                 / 'resources' / 'routes' / 'wuhan-demo-own.route.json')


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


class WuhanPublisher(BaseMqttPublisher):
    def __init__(self, config, route_path=DEFAULT_ROUTE):
        super().__init__(config)
        self.routes = build_routes(route_path)
        self.started = None
        self.finished_at = None

    def before_loop(self):
        self.started = time.monotonic()
        print('Wuhan Yangtze: 123456789 downstream, 900001222 upstream, 900002222 overtaken')

    def publish_tick(self):
        elapsed = time.monotonic() - self.started
        timestamp = format_msg_time(datetime.now())
        stopped = True
        for vessel in self.routes:
            (lon, lat), speed, heading = vessel.sample(elapsed)
            stopped = stopped and speed == 0
            payload = json.dumps({
                'MSGTIME': timestamp, 'MMSI': vessel.mmsi,
                'LAT': round(lat, 6), 'LON': round(lon, 6),
                'SOG': round(speed, 2), 'COG': round(heading, 1),
                'HEADING': round(heading, 1),
            })
            self.publish(payload)
            print(payload, flush=True)
        if stopped:
            if self.finished_at is None:
                self.finished_at = elapsed
            if elapsed - self.finished_at >= 35:
                self._running = False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='localhost')
    parser.add_argument('--port', type=int, default=1883)
    parser.add_argument('--topic', default='usv/AisMessage')
    parser.add_argument('--interval', type=float, default=1.0)
    parser.add_argument('--ticks', type=int, default=0, help='Optional upper bound; 0 completes one run')
    parser.add_argument('--username', default=None)
    parser.add_argument('--password', default=None)
    parser.add_argument('--route', default=str(DEFAULT_ROUTE), help='own-ship planned-route JSON')
    args = parser.parse_args()
    if not math.isfinite(args.interval) or args.interval <= 0 or args.ticks < 0:
        parser.error('interval must be finite and positive; ticks must be non-negative')
    config = MqttConfig(host=args.host, port=args.port, topic=args.topic,
                        username=args.username, password=args.password)
    WuhanPublisher(config, args.route).run(interval=args.interval, ticks=args.ticks)


if __name__ == '__main__':
    main()
