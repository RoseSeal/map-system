import argparse
import json
import random
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Dict, List, Optional

from mqtt_publisher_base import BaseMqttPublisher, MqttConfig

TOPIC = "usv/Weather"


@dataclass(frozen=True)
class WeatherScene:
    weather_code: str
    visibility_nm: float
    precipitation_mm_per_hr: float
    wind_speed_kn: float
    wind_direction_from_deg: float
    surface_current_speed_kn: float
    surface_current_set_deg: float
    sea_state: int


SCENES: Dict[str, WeatherScene] = {
    "clear": WeatherScene(
        weather_code="CLEAR",
        visibility_nm=10.0,
        precipitation_mm_per_hr=0.0,
        wind_speed_kn=5.0,
        wind_direction_from_deg=210.0,
        surface_current_speed_kn=0.4,
        surface_current_set_deg=95.0,
        sea_state=2,
    ),
    "fog": WeatherScene(
        weather_code="FOG",
        visibility_nm=0.8,
        precipitation_mm_per_hr=0.0,
        wind_speed_kn=3.0,
        wind_direction_from_deg=225.0,
        surface_current_speed_kn=0.4,
        surface_current_set_deg=90.0,
        sea_state=2,
    ),
    "rain": WeatherScene(
        weather_code="RAIN",
        visibility_nm=3.0,
        precipitation_mm_per_hr=8.0,
        wind_speed_kn=15.0,
        wind_direction_from_deg=190.0,
        surface_current_speed_kn=1.0,
        surface_current_set_deg=105.0,
        sea_state=4,
    ),
    "storm": WeatherScene(
        weather_code="STORM",
        visibility_nm=1.5,
        precipitation_mm_per_hr=14.0,
        wind_speed_kn=35.0,
        wind_direction_from_deg=160.0,
        surface_current_speed_kn=2.8,
        surface_current_set_deg=120.0,
        sea_state=7,
    ),
    "zoned_fog": WeatherScene(
        weather_code="CLEAR",
        visibility_nm=10.0,
        precipitation_mm_per_hr=0.0,
        wind_speed_kn=5.0,
        wind_direction_from_deg=270.0,
        surface_current_speed_kn=0.3,
        surface_current_set_deg=90.0,
        sea_state=2,
    ),
}

# Static zone definitions for scenes that carry weather_zones.
# Coordinates follow GeoJSON [longitude, latitude] convention.
_ZONED_FOG_ZONES: List[Dict] = [
    {
        "zone_id": "fog-bank-east",
        "weather_code": "FOG",
        "visibility_nm": 0.8,
        "precipitation_mm_per_hr": 0.0,
        "wind": {"speed_kn": 3.0, "direction_from_deg": 225},
        "surface_current": {"speed_kn": 0.4, "set_deg": 90},
        "sea_state": 2,
        "geometry": {
            "type": "Polygon",
            "coordinates": [
                [
                    [114.30, 30.52],
                    [114.34, 30.52],
                    [114.34, 30.56],
                    [114.30, 30.56],
                    [114.30, 30.52],
                ]
            ],
        },
    }
]


class WeatherMqttPublisher(BaseMqttPublisher):
    def __init__(self, config: MqttConfig, scene_name: str, jitter: float) -> None:
        super().__init__(config)
        self.scene_name = scene_name
        self.scene = SCENES[scene_name]
        self.jitter = max(0.0, jitter)

    def before_loop(self) -> None:
        print(
            f"Publishing weather scene '{self.scene_name}' to {self.config.topic} "
            f"(jitter={self.jitter:.2f})"
        )

    def publish_tick(self) -> None:
        payload = json.dumps(self._build_payload(), ensure_ascii=False)
        self.publish(payload)
        print(payload)

    def _build_payload(self) -> Dict[str, object]:
        visibility_nm = self._jitter_scalar(self.scene.visibility_nm)
        precipitation_mm_per_hr = self._jitter_scalar(self.scene.precipitation_mm_per_hr)
        wind_speed_kn = self._jitter_scalar(self.scene.wind_speed_kn)
        wind_direction_from_deg = self._jitter_direction(self.scene.wind_direction_from_deg)
        surface_current_speed_kn = self._jitter_scalar(self.scene.surface_current_speed_kn)
        surface_current_set_deg = self._jitter_direction(self.scene.surface_current_set_deg)
        sea_state = self._jitter_sea_state(self.scene.sea_state)

        payload: Dict[str, object] = {
            "weather_code": self.scene.weather_code,
            "visibility_nm": round(visibility_nm, 2),
            "precipitation_mm_per_hr": round(precipitation_mm_per_hr, 2),
            "wind": {
                "speed_kn": round(wind_speed_kn, 2),
                "direction_from_deg": round(wind_direction_from_deg, 1),
            },
            "surface_current": {
                "speed_kn": round(surface_current_speed_kn, 2),
                "set_deg": round(surface_current_set_deg, 1),
            },
            "sea_state": sea_state,
            "timestamp_utc": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        }

        zones = self._build_zones()
        if zones is not None:
            payload["weather_zones"] = zones

        return payload

    def _build_zones(self) -> Optional[List[Dict]]:
        if self.scene_name == "zoned_fog":
            return _ZONED_FOG_ZONES
        return None

    def _jitter_scalar(self, value: float) -> float:
        if self.jitter <= 0.0:
            return max(0.0, value)

        amplitude = max(abs(value), 1.0) * self.jitter
        return max(0.0, value + random.uniform(-amplitude, amplitude))

    def _jitter_direction(self, value: float) -> float:
        if self.jitter <= 0.0:
            return value % 360.0

        delta = random.uniform(-36.0 * self.jitter, 36.0 * self.jitter)
        return (value + delta) % 360.0

    def _jitter_sea_state(self, value: int) -> int:
        if self.jitter <= 0.0:
            return value

        step = 1 if random.random() < self.jitter else 0
        signed_step = step if random.random() >= 0.5 else -step
        return int(min(9, max(0, value + signed_step)))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Publish weather snapshots to MQTT")
    parser.add_argument("--host", default="localhost", help="MQTT broker host")
    parser.add_argument("--port", type=int, default=1883, help="MQTT broker port")
    parser.add_argument("--topic", default=TOPIC, help="MQTT topic")
    parser.add_argument("--interval", type=float, default=10.0, help="Publish interval in seconds")
    parser.add_argument("--ticks", type=int, default=0, help="Number of publish ticks, 0 means run forever")
    parser.add_argument(
        "--scene",
        default="clear",
        choices=sorted(SCENES.keys()),
        help="Built-in weather scene",
    )
    parser.add_argument(
        "--jitter",
        type=float,
        default=0.0,
        help="Relative jitter factor applied to scalar values (e.g. 0.1 = ±10%%)",
    )
    parser.add_argument("--seed", type=int, default=None, help="Optional random seed")
    parser.add_argument("--username", default=None, help="MQTT username")
    parser.add_argument("--password", default=None, help="MQTT password")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.seed is not None:
        random.seed(args.seed)

    config = MqttConfig(
        host=args.host,
        port=args.port,
        topic=args.topic,
        username=args.username,
        password=args.password,
    )
    publisher = WeatherMqttPublisher(config=config, scene_name=args.scene, jitter=args.jitter)
    publisher.run(interval=max(0.0, args.interval), ticks=max(0, args.ticks))


if __name__ == "__main__":
    main()
