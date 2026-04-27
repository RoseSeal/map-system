import argparse
import os
import random
from pathlib import Path
from typing import Dict
from typing import Tuple
from urllib.parse import urlparse

from mqtt_publisher_base import MqttConfig
from weather_mqtt_publisher import SCENES, TOPIC, WeatherMqttPublisher


DEFAULT_REMOTE_BROKER = "tcp://f91b3b75.ala.dedicated.aliyun.emqxcloud.cn:1883"
_REPO_ROOT = Path(__file__).resolve().parents[1]
_BACKEND_RESOURCES = _REPO_ROOT / "backend" / "map-service" / "src" / "main" / "resources"
_APPLICATION_PROPERTIES = _BACKEND_RESOURCES / "application.properties"
_APPLICATION_LOCAL_PROPERTIES = _BACKEND_RESOURCES / "application-local.properties"


def _load_properties(path: Path) -> Dict[str, str]:
    if not path.exists():
        return {}

    values: Dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or line.startswith("!"):
            continue

        if "=" in line:
            key, value = line.split("=", 1)
        elif ":" in line:
            key, value = line.split(":", 1)
        else:
            continue

        values[key.strip()] = value.strip()

    return values


def _resolve_host_port(broker: str, host: str | None, port: int | None) -> Tuple[str, int]:
    if host is not None:
        return host, 1883 if port is None else port

    parsed = urlparse(broker if "://" in broker else f"tcp://{broker}")
    resolved_host = parsed.hostname or "localhost"
    resolved_port = parsed.port or 1883

    if port is not None:
        resolved_port = port

    return resolved_host, resolved_port


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Publish simulated weather snapshots to remote MQTT broker"
    )
    parser.add_argument(
        "--broker",
        default=None,
        help="Remote broker URL, e.g. tcp://host:1883 (optional)",
    )
    parser.add_argument("--host", default=None, help="Override MQTT broker host")
    parser.add_argument("--port", type=int, default=None, help="Override MQTT broker port")
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
        help="Relative jitter factor applied to scalar values (e.g. 0.1 = +-10%%)",
    )
    parser.add_argument("--seed", type=int, default=None, help="Optional random seed")
    parser.add_argument(
        "--username",
        default=None,
        help="MQTT username (optional)",
    )
    parser.add_argument(
        "--password",
        default=None,
        help="MQTT password (optional)",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.seed is not None:
        random.seed(args.seed)

    app_properties = _load_properties(_APPLICATION_PROPERTIES)
    app_local_properties = _load_properties(_APPLICATION_LOCAL_PROPERTIES)

    broker = (
        args.broker
        or os.getenv("MQTT_REMOTE_BROKER")
        or app_properties.get("mqtt.remote.broker")
        or DEFAULT_REMOTE_BROKER
    )
    username = (
        args.username
        or os.getenv("MQTT_REMOTE_USERNAME")
        or app_local_properties.get("MQTT_REMOTE_USERNAME")
    )
    password = (
        args.password
        or os.getenv("MQTT_REMOTE_PASSWORD")
        or app_local_properties.get("MQTT_REMOTE_PASSWORD")
    )

    host, port = _resolve_host_port(broker, args.host, args.port)

    config = MqttConfig(
        host=host,
        port=port,
        topic=args.topic,
        username=username,
        password=password,
    )
    publisher = WeatherMqttPublisher(config=config, scene_name=args.scene, jitter=args.jitter)
    publisher.run(interval=max(0.0, args.interval), ticks=max(0, args.ticks))


if __name__ == "__main__":
    main()
