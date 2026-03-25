import argparse
import csv
import json
from typing import Iterator

from mqtt_publisher_base import BaseMqttPublisher, MqttConfig

TOPIC = "usv/AisMessage"
DEFAULT_CSV_PATH = "/mnt/d/archive_old_projects/Project_backup/data/AIS_data/NBSDC data/ais_data.csv"


class CsvAisReplayPublisher(BaseMqttPublisher):
    def __init__(self, config: MqttConfig, csv_path: str) -> None:
        super().__init__(config)
        self.csv_path = csv_path
        self._rows: Iterator[dict] | None = None
        self._file = None

    def before_loop(self) -> None:
        self._file = open(self.csv_path, "r", encoding="utf-8")
        self._rows = iter(csv.DictReader(self._file))
        print(f"Streaming AIS CSV from {self.csv_path} to {self.config.topic}")

    def publish_tick(self) -> None:
        if self._rows is None:
            raise RuntimeError("CSV reader is not initialized")

        try:
            row = next(self._rows)
        except StopIteration:
            self._running = False
            return

        data = {
            "MSGTIME": row["MSGTIME"],
            "MMSI": row["MMSI"],
            "LAT": row["LAT"],
            "LON": row["LON"],
            "SOG": row["SpeedOverGround"],
            "COG": row["CourseOverGround"],
            "HEADING": row["TrueHeading"],
        }
        payload = json.dumps(data, ensure_ascii=False)
        self.publish(payload)
        print(f"Published data for MMSI: {data['MMSI']}")

    def after_loop(self) -> None:
        if self._file is not None:
            self._file.close()
            self._file = None


def main() -> None:
    parser = argparse.ArgumentParser(description="Replay AIS CSV rows to MQTT")
    parser.add_argument("--host", default="localhost", help="MQTT broker host")
    parser.add_argument("--port", type=int, default=1883, help="MQTT broker port")
    parser.add_argument("--topic", default=TOPIC, help="MQTT topic")
    parser.add_argument("--interval", type=float, default=0.5, help="Publish interval in seconds")
    parser.add_argument("--csv", default=DEFAULT_CSV_PATH, help="AIS CSV file path")
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
    publisher = CsvAisReplayPublisher(config, args.csv)
    publisher.run(interval=args.interval)


if __name__ == "__main__":
    main()