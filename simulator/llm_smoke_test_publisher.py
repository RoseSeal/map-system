import argparse
import json
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import List

from mqtt_publisher_base import BaseMqttPublisher, MqttConfig

TOPIC = "usv/AisMessage"
OWN_MMSI = "123456789"


@dataclass
class TimedAisMessage:
    payload: str


class LlmSmokeTestPublisher(BaseMqttPublisher):
    def __init__(self, config: MqttConfig, messages: List[TimedAisMessage]) -> None:
        super().__init__(config)
        self.messages = messages
        self.index = 0

    def before_loop(self) -> None:
        print(f"Publishing {len(self.messages)} AIS messages to {self.config.topic} for LLM smoke testing")
        print("Scenario: 1 own ship + 1 head-on target, minimal messages to trigger a single LLM explanation")

    def publish_tick(self) -> None:
        if self.index >= len(self.messages):
            self._running = False
            return

        message = self.messages[self.index]
        self.publish(message.payload)
        print(message.payload)
        self.index += 1


def format_msg_time(msg_time: datetime) -> str:
    return f"{msg_time.year}-{msg_time.month}-{msg_time.day} {msg_time:%H:%M:%S}"


def to_payload(msg_time: datetime, mmsi: str, lon: float, lat: float, sog: float, cog: float, heading: float) -> str:
    data = {
        "MSGTIME": format_msg_time(msg_time),
        "MMSI": mmsi,
        "LAT": round(lat, 6),
        "LON": round(lon, 6),
        "SOG": round(sog, 2),
        "COG": round(cog, 1),
        "HEADING": round(heading, 1),
    }
    return json.dumps(data, ensure_ascii=False)


def build_messages(target_mmsi: str) -> List[TimedAisMessage]:
    base_time = datetime.now()
    return [
        TimedAisMessage(
            payload=to_payload(
                base_time,
                OWN_MMSI,
                114.200000,
                30.578000,
                12.0,
                90.0,
                90.0,
            )
        ),
        TimedAisMessage(
            payload=to_payload(
                base_time + timedelta(seconds=1),
                target_mmsi,
                114.206000,
                30.578000,
                12.0,
                270.0,
                270.0,
            )
        ),
        # TimedAisMessage(
        #     payload=to_payload(
        #         base_time + timedelta(seconds=2),
        #         OWN_MMSI,
        #         114.201000,
        #         30.578000,
        #         12.0,
        #         90.0,
        #         90.0,
        #     )
        # ),
        # TimedAisMessage(
        #     payload=to_payload(
        #         base_time + timedelta(seconds=3),
        #         target_mmsi,
        #         114.205000,
        #         30.578000,
        #         12.0,
        #         270.0,
        #         270.0,
        #     )
        # ),
    ]


def main() -> None:
    parser = argparse.ArgumentParser(description="Publish a minimal AIS scenario to trigger one LLM risk explanation")
    parser.add_argument("--host", default="localhost", help="MQTT broker host")
    parser.add_argument("--port", type=int, default=1883, help="MQTT broker port")
    parser.add_argument("--topic", default=TOPIC, help="MQTT topic")
    parser.add_argument("--interval", type=float, default=0.6, help="Publish interval in seconds")
    parser.add_argument("--target-mmsi", default="900009999", help="Target vessel MMSI")
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
    publisher = LlmSmokeTestPublisher(config, build_messages(args.target_mmsi))
    publisher.run(interval=args.interval, ticks=len(publisher.messages))


if __name__ == "__main__":
    main()
