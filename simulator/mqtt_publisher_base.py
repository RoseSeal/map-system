import signal
import time
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Optional

import paho.mqtt.client as mqtt


@dataclass
class MqttConfig:
    host: str = "localhost"
    port: int = 1883
    topic: str = "usv/AisMessage"
    keepalive: int = 60
    client_id: Optional[str] = None
    username: Optional[str] = None
    password: Optional[str] = None


class BaseMqttPublisher(ABC):
    def __init__(self, config: MqttConfig) -> None:
        self.config = config
        self.client = mqtt.Client(client_id=config.client_id)
        if config.username is not None:
            self.client.username_pw_set(config.username, config.password)
        self._running = False

    def connect(self) -> None:
        self.client.connect(self.config.host, self.config.port, self.config.keepalive)
        self.client.loop_start()

    def disconnect(self) -> None:
        self.client.loop_stop()
        self.client.disconnect()

    def publish(self, payload: str, topic: Optional[str] = None) -> None:
        self.client.publish(topic or self.config.topic, payload)

    def install_signal_handlers(self) -> None:
        def stop_handler(signum, frame):
            self._running = False

        signal.signal(signal.SIGINT, stop_handler)
        signal.signal(signal.SIGTERM, stop_handler)

    @abstractmethod
    def publish_tick(self) -> None:
        pass

    def before_loop(self) -> None:
        pass

    def after_loop(self) -> None:
        pass

    def run(self, interval: float, ticks: int = 0) -> None:
        self.connect()
        self.install_signal_handlers()
        self.before_loop()
        self._running = True
        tick = 0

        try:
            while self._running and (ticks == 0 or tick < ticks):
                self.publish_tick()
                tick += 1
                if interval > 0:
                    time.sleep(interval)
        finally:
            try:
                self.after_loop()
            finally:
                self.disconnect()