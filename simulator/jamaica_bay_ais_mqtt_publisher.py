import argparse
from email import parser
import json
import math
import signal
import time
from dataclasses import dataclass
from datetime import datetime
from typing import List, Tuple

import paho.mqtt.client as mqtt

TOPIC = "usv/AisMessage"
OWN_MMSI = "123456789"
EARTH_RADIUS_M = 6371000.0
KNOT_TO_MPS = 0.514444

LonLat = Tuple[float, float]


@dataclass
class VesselRoute:
    mmsi: str
    waypoints: List[LonLat]
    sog_kn: float
    loop: bool = True
    segment_index: int = 0
    progress_m: float = 0.0

    def current_segment(self) -> Tuple[LonLat, LonLat]:
        start = self.waypoints[self.segment_index]
        next_index = self.segment_index + 1
        if next_index >= len(self.waypoints):
            next_index = 0 if self.loop else self.segment_index
        end = self.waypoints[next_index]
        return start, end

    def step(self, delta_seconds: float) -> Tuple[LonLat, float]:
        if len(self.waypoints) < 2:
            return self.waypoints[0], 0.0

        remaining_m = self.sog_kn * KNOT_TO_MPS * delta_seconds
        while remaining_m > 0:
            start, end = self.current_segment()
            segment_length_m = haversine_m(start, end)
            if segment_length_m <= 0.1:
                self.advance_segment()
                continue

            left_on_segment = segment_length_m - self.progress_m
            if remaining_m < left_on_segment:
                self.progress_m += remaining_m
                remaining_m = 0.0
            else:
                remaining_m -= left_on_segment
                self.advance_segment()

        start, end = self.current_segment()
        segment_length_m = max(haversine_m(start, end), 0.1)
        ratio = min(max(self.progress_m / segment_length_m, 0.0), 1.0)
        position = interpolate(start, end, ratio)
        heading = bearing_deg(start, end)
        return position, heading

    def advance_segment(self) -> None:
        self.progress_m = 0.0
        self.segment_index += 1
        if self.segment_index >= len(self.waypoints) - 1:
            self.segment_index = 0 if self.loop else len(self.waypoints) - 2


def haversine_m(a: LonLat, b: LonLat) -> float:
    lon1, lat1 = map(math.radians, a)
    lon2, lat2 = map(math.radians, b)
    dlon = lon2 - lon1
    dlat = lat2 - lat1
    sin_dlat = math.sin(dlat / 2)
    sin_dlon = math.sin(dlon / 2)
    h = sin_dlat * sin_dlat + math.cos(lat1) * math.cos(lat2) * sin_dlon * sin_dlon
    return 2 * EARTH_RADIUS_M * math.asin(math.sqrt(h))


def interpolate(a: LonLat, b: LonLat, ratio: float) -> LonLat:
    lon = a[0] + (b[0] - a[0]) * ratio
    lat = a[1] + (b[1] - a[1]) * ratio
    return lon, lat


def bearing_deg(a: LonLat, b: LonLat) -> float:
    lon1, lat1 = map(math.radians, a)
    lon2, lat2 = map(math.radians, b)
    dlon = lon2 - lon1
    y = math.sin(dlon) * math.cos(lat2)
    x = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(dlon)
    bearing = math.degrees(math.atan2(y, x))
    return (bearing + 360.0) % 360.0


def format_msg_time(msg_time: datetime) -> str:
    return f"{msg_time.year}-{msg_time.month}-{msg_time.day} {msg_time:%H:%M:%S}"


def build_routes() -> List[VesselRoute]:
    """
    Jamaica Bay, New York, USA
    Lat: 40.57N ~ 40.64N  |  Lon: 73.78W ~ 73.92W

    OWN_SHIP  : eastbound through the main channel (Rockaway Inlet -> JFK waterfront)
    Target 1  : westbound, head-on risk with own ship
    Target 2  : northbound cross-traffic from Rockaway
    Target 3  : southbound from JFK cargo pier
    """
    return [
        # OWN SHIP
        VesselRoute(
            mmsi=OWN_MMSI,
            sog_kn=10.0,
            waypoints=[
                (-73.866624, 40.592792),
                (-73.859365, 40.616033),
                (-73.847233, 40.619305),
                (-73.835084, 40.640587),
                (-73.816614, 40.639087),
                (-73.805607, 40.598807),
                (-73.821623, 40.590491),
                (-73.837910, 40.587080),
            ],
        ),

        # TARGET 1 (T-101): Central patrol
        VesselRoute(
            mmsi="366000002",
            sog_kn=8.5,
            waypoints=[
                (-73.88870, 40.61980),
                (-73.86295, 40.63440),
                (-73.86052, 40.61412),
                (-73.88222, 40.60926),
            ],
        ),
        # TARGET 2 (T-102): Eastern patrol
        VesselRoute(
            mmsi="366000003",
            sog_kn=6.0,
            waypoints=[
                (-73.86031, 40.61372),
                (-73.84389, 40.61818),
                (-73.85241, 40.62710),
                (-73.86031, 40.62345),
            ],
        ),
        # TARGET 3 (T-103): Northeastern patrol
        VesselRoute(
            mmsi="366000004",
            sog_kn=5.0,
            waypoints=[
                (-73.84003, 40.63359),
                (-73.83253, 40.64353),
                (-73.81915, 40.63967),
                (-73.82503, 40.64576),
                (-73.83943, 40.64130),
            ],
        ),
        # TARGET 4 (T-104): Far eastern patrol
        VesselRoute(
            mmsi="366000005",
            sog_kn=6.5,
            waypoints=[
                (-73.80941, 40.61331),
                (-73.80657, 40.63237),
                (-73.79319, 40.63055),
                (-73.79988, 40.61757),
                (-73.80799, 40.61088),
            ],
        ),
        # TARGET 5 (T-105): Southern patrol
        VesselRoute(
            mmsi="366000006",
            sog_kn=5.5,
            waypoints=[
                (-73.82746, 40.60074),
                (-73.83679, 40.60337),
                (-73.84612, 40.59830),
                (-73.83436, 40.59912),
            ],
        ),
    ]


def to_payload(msg_time: datetime, route: VesselRoute, lon: float, lat: float, heading: float) -> str:
    data = {
        "MSGTIME": format_msg_time(msg_time),
        "MMSI": route.mmsi,
        "LAT": round(lat, 6),
        "LON": round(lon, 6),
        "SOG": round(route.sog_kn, 2),
        "COG": round(heading, 1),
        "HEADING": round(heading, 1),
    }
    return json.dumps(data, ensure_ascii=False)


def publish_tick(client, routes, topic, delta_seconds, speed_scale=1.0):
    msg_time = datetime.now()
    for route in routes:
        original_sog = route.sog_kn
        route.sog_kn *= speed_scale
        (lon, lat), heading = route.step(delta_seconds)
        route.sog_kn = original_sog          # 还原，不污染状态
        payload = to_payload(msg_time, route, lon, lat, heading)
        client.publish(topic, payload)
        print(payload)


def main() -> None:
    parser = argparse.ArgumentParser(description="Publish Jamaica Bay AIS demo routes to MQTT")
    parser.add_argument("--host", default="localhost", help="MQTT broker host")
    parser.add_argument("--port", type=int, default=1883, help="MQTT broker port")
    parser.add_argument("--topic", default=TOPIC, help="MQTT topic")
    parser.add_argument("--interval", type=float, default=1.0, help="Publish interval in seconds")
    parser.add_argument("--ticks", type=int, default=0, help="Number of publish ticks, 0 means run forever")
    parser.add_argument("--speed-scale", type=float, default=1.0, help="Speed multiplier for demo")
    args = parser.parse_args()

    client = mqtt.Client()
    client.connect(args.host, args.port, 60)
    client.loop_start()

    routes = build_routes()
    running = True

    def stop_handler(signum, frame):
        nonlocal running
        running = False

    signal.signal(signal.SIGINT, stop_handler)
    signal.signal(signal.SIGTERM, stop_handler)

    tick = 0
    print(f"Publishing Jamaica Bay routes to {args.topic} every {args.interval:.1f}s")
    print("Routes: own ship (eastbound) + 3 targets -- head-on / crossing / southbound scenarios")

    try:
        while running and (args.ticks == 0 or tick < args.ticks):
            publish_tick(client, routes, args.topic, args.interval, args.speed_scale)
            tick += 1
            time.sleep(args.interval)
    finally:
        client.loop_stop()
        client.disconnect()
        print("Simulator stopped.")


        
if __name__ == "__main__":
    main()