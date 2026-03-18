import argparse
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
    return [
        VesselRoute(
            mmsi=OWN_MMSI,
            sog_kn=11.5,
            waypoints=[
                (114.2000, 30.5780),
                (114.2100, 30.5780),
                (114.2200, 30.5780),
                (114.2300, 30.5780),
            ],
        ),
        VesselRoute(
            mmsi="900001222",
            sog_kn=11.0,
            waypoints=[
                (114.2320, 30.5782),
                (114.2220, 30.5781),
                (114.2120, 30.5780),
                (114.2020, 30.5779),
            ],
        ),
        VesselRoute(
            mmsi="900002222",
            sog_kn=9.0,
            waypoints=[
                (114.2140, 30.5680),
                (114.2140, 30.5730),
                (114.2140, 30.5780),
                (114.2140, 30.5830),
                (114.2140, 30.5880),
            ],
        ),
        VesselRoute(
            mmsi="900003222",
            sog_kn=7.0,
            waypoints=[
                (114.2050, 30.5845),
                (114.2140, 30.5815),
                (114.2230, 30.5790),
                (114.2320, 30.5760),
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


def publish_tick(client: mqtt.Client, routes: List[VesselRoute], topic: str, delta_seconds: float) -> None:
    msg_time = datetime.now()
    for route in routes:
        (lon, lat), heading = route.step(delta_seconds)
        payload = to_payload(msg_time, route, lon, lat, heading)
        client.publish(topic, payload)
        print(payload)


def main() -> None:
    parser = argparse.ArgumentParser(description="Publish continuous CPA/TCPA demo routes to MQTT")
    parser.add_argument("--host", default="localhost", help="MQTT broker host")
    parser.add_argument("--port", type=int, default=1883, help="MQTT broker port")
    parser.add_argument("--topic", default=TOPIC, help="MQTT topic")
    parser.add_argument("--interval", type=float, default=1.0, help="Publish interval in seconds")
    parser.add_argument("--ticks", type=int, default=0, help="Number of publish ticks, 0 means run forever")
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
    print(f"Publishing continuous routes to {args.topic} every {args.interval:.1f}s")
    print("Routes: own ship + 3 targets around Wuhan demo area")

    try:
        while running and (args.ticks == 0 or tick < args.ticks):
            publish_tick(client, routes, args.topic, args.interval)
            tick += 1
            time.sleep(args.interval)
    finally:
        client.loop_stop()
        client.disconnect()
        print("Simulator stopped.")


if __name__ == "__main__":
    main()
