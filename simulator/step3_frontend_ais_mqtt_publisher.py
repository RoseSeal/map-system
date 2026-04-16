import argparse
import json
import math
from dataclasses import dataclass
from datetime import datetime
from typing import List, Tuple

from mqtt_publisher_base import BaseMqttPublisher, MqttConfig

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


@dataclass
class VesselScenario:
    route: VesselRoute
    confidence_mode: str = "normal"
    pulse_every: int = 0
    force_undefined_encounter: bool = False


class Step3FrontendAisPublisher(BaseMqttPublisher):
    def __init__(
        self,
        config: MqttConfig,
        scenarios: List[VesselScenario],
        interval: float,
        speed_scale: float,
    ) -> None:
        super().__init__(config)
        self.scenarios = scenarios
        self.interval = interval
        self.speed_scale = speed_scale
        self.tick = 0

    def before_loop(self) -> None:
        print(f"Publishing Step 3 frontend test routes to {self.config.topic} every {self.interval:.1f}s")
        print("Scenario coverage:")
        print("- HEAD_ON / OVERTAKING / CROSSING / UNDEFINED encounter types")
        print("- Risk confidence tiers via periodic kinematic quality pulses")
        print("- Multiple relative geometry patterns to produce risk score variation")

    def publish_tick(self) -> None:
        msg_time = datetime.now()
        self.tick += 1

        for scenario in self.scenarios:
            base_sog = scenario.route.sog_kn
            scenario.route.sog_kn *= self.speed_scale
            (lon, lat), heading = scenario.route.step(self.interval)
            scenario.route.sog_kn = base_sog

            payload = to_payload(
                msg_time=msg_time,
                scenario=scenario,
                lon=lon,
                lat=lat,
                heading=heading,
                tick=self.tick,
                speed_scale=self.speed_scale,
            )
            self.publish(payload)
            print(payload)


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


def apply_quality_profile(
    scenario: VesselScenario,
    lon: float,
    lat: float,
    sog: float,
    heading: float,
    tick: int,
) -> Tuple[float, float, float, float, float]:
    cog = heading
    heading_out = heading

    if scenario.force_undefined_encounter:
        # COG 360 marks invalid course and drives encounter_type to UNDEFINED.
        cog = 360.0
        # HEADING 511 marks unknown heading in AIS protocol.
        heading_out = 511.0
        return lon, lat, sog, cog, heading_out

    pulse = scenario.pulse_every > 0 and tick > 0 and (tick % scenario.pulse_every == 0)
    if not pulse:
        return lon, lat, sog, cog, heading_out

    if scenario.confidence_mode == "medium":
        # Trigger POSITION_JUMP + SOG_JUMP while keeping course mostly stable.
        lon += 0.0014
        lat += 0.0004
        sog = min(29.5, sog + 11.5)
        cog = (heading + 20.0) % 360.0
        heading_out = cog
        return lon, lat, sog, cog, heading_out

    if scenario.confidence_mode == "low":
        # Trigger POSITION_JUMP + SOG_JUMP + COG_JUMP for lower confidence.
        lon -= 0.0022
        lat += 0.0012
        sog = min(29.5, sog + 13.5)
        cog = (heading + 170.0) % 360.0
        heading_out = cog
        return lon, lat, sog, cog, heading_out

    return lon, lat, sog, cog, heading_out


def build_scenarios() -> List[VesselScenario]:
    return [
        VesselScenario(
            route=VesselRoute(
                mmsi=OWN_MMSI,
                sog_kn=10.5,
                waypoints=[
                    (-73.8610, 40.6125),
                    (-73.8480, 40.6125),
                    (-73.8350, 40.6125),
                    (-73.8220, 40.6125),
                ],
            ),
        ),
        VesselScenario(
            route=VesselRoute(
                mmsi="366100101",
                sog_kn=11.5,
                waypoints=[
                    (-73.8205, 40.6128),
                    (-73.8330, 40.6128),
                    (-73.8455, 40.6128),
                    (-73.8580, 40.6128),
                ],
            ),
        ),
        VesselScenario(
            route=VesselRoute(
                mmsi="366100102",
                sog_kn=9.0,
                waypoints=[
                    (-73.8420, 40.6005),
                    (-73.8420, 40.6070),
                    (-73.8420, 40.6135),
                    (-73.8420, 40.6200),
                ],
            ),
            confidence_mode="medium",
            pulse_every=6,
        ),
        VesselScenario(
            route=VesselRoute(
                mmsi="366100103",
                sog_kn=14.0,
                waypoints=[
                    (-73.8720, 40.6118),
                    (-73.8600, 40.6119),
                    (-73.8480, 40.6120),
                    (-73.8360, 40.6121),
                ],
            ),
            confidence_mode="low",
            pulse_every=4,
        ),
        VesselScenario(
            route=VesselRoute(
                mmsi="366100104",
                sog_kn=7.0,
                waypoints=[
                    (-73.8540, 40.6210),
                    (-73.8460, 40.6212),
                    (-73.8380, 40.6208),
                    (-73.8460, 40.6205),
                ],
            ),
            force_undefined_encounter=True,
        ),
    ]


def to_payload(
    msg_time: datetime,
    scenario: VesselScenario,
    lon: float,
    lat: float,
    heading: float,
    tick: int,
    speed_scale: float,
) -> str:
    sog = scenario.route.sog_kn * speed_scale
    lon, lat, sog, cog, heading_out = apply_quality_profile(
        scenario=scenario,
        lon=lon,
        lat=lat,
        sog=sog,
        heading=heading,
        tick=tick,
    )

    data = {
        "MSGTIME": format_msg_time(msg_time),
        "MMSI": scenario.route.mmsi,
        "LAT": round(lat, 6),
        "LON": round(lon, 6),
        "SOG": round(sog, 2),
        "COG": round(cog, 1),
        "HEADING": round(heading_out, 1),
    }
    return json.dumps(data, ensure_ascii=False)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Publish Step 3 frontend enhancement AIS routes to MQTT"
    )
    parser.add_argument("--host", default="localhost", help="MQTT broker host")
    parser.add_argument("--port", type=int, default=1883, help="MQTT broker port")
    parser.add_argument("--topic", default=TOPIC, help="MQTT topic")
    parser.add_argument("--interval", type=float, default=1.0, help="Publish interval in seconds")
    parser.add_argument("--ticks", type=int, default=0, help="Number of publish ticks, 0 means run forever")
    parser.add_argument("--speed-scale", type=float, default=1.0, help="Speed multiplier for demo")
    parser.add_argument("--username", default=None, help="MQTT username")
    parser.add_argument("--password", default=None, help="MQTT password")
    args = parser.parse_args()

    config = MqttConfig(
        host=args.host,
        port=args.port,
        topic=args.topic,
        username=args.username,
        password=args.password,
    )
    publisher = Step3FrontendAisPublisher(
        config=config,
        scenarios=build_scenarios(),
        interval=args.interval,
        speed_scale=args.speed_scale,
    )
    publisher.run(interval=args.interval, ticks=args.ticks)


if __name__ == "__main__":
    main()
