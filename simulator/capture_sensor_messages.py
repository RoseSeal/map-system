"""订阅三路 MQTT，收到 SUBACK 后打印 READY，有限时长保存实际消息。"""
import argparse
import json
import math
import threading
import time
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
import paho.mqtt.client as mqtt
from verify_sensor_observations import strict_json


def capture(host, port, seconds, output, ais_topic='usv/AisMessage'):
    if not math.isfinite(seconds) or seconds <= 0:
        raise ValueError('seconds 必须为有限正数')
    path = Path(output)
    meta = path.with_suffix(path.suffix + '.meta.json')
    if path.exists() or meta.exists():
        raise FileExistsError('输出已存在，拒绝覆盖')
    topics = (ais_topic, 'usv/RadarObservation', 'usv/VisionObservation')
    if len(set(topics)) != 3 or not ais_topic or any(c in ais_topic for c in '+#\x00'):
        raise ValueError('AIS topic 无效或重复')
    ready, failed = threading.Event(), threading.Event()
    counts, errors = Counter(), []
    started = datetime.now(timezone.utc).isoformat()
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
    with path.open('x', encoding='utf-8', newline='\n') as stream:
        def on_connect(c, userdata, flags, reason, properties):
            if reason.is_failure:
                errors.append(str(reason))
                failed.set()
            else:
                c.subscribe([(topic, 0) for topic in topics])
        def on_subscribe(c, userdata, mid, reasons, properties):
            if len(reasons) != 3 or any(r.is_failure for r in reasons):
                errors.append('订阅被拒绝')
                failed.set()
            else:
                ready.set()
        def on_message(c, userdata, msg):
            if msg.retain:
                return
            try:
                payload = strict_json(msg.payload.decode('utf-8'))
                if not isinstance(payload, dict):
                    raise ValueError('payload 必须为对象')
                stream.write(json.dumps({'topic': msg.topic, 'payload': payload},
                                        ensure_ascii=False, allow_nan=False) + '\n')
                stream.flush()
                counts[msg.topic] += 1
            except (ValueError, UnicodeError, OSError) as exc:
                errors.append(str(exc))
                failed.set()
        def on_disconnect(c, userdata, flags, reason, properties):
            if reason.is_failure:
                errors.append('连接中断: ' + str(reason))
                failed.set()
        client.on_connect, client.on_subscribe = on_connect, on_subscribe
        client.on_message, client.on_disconnect = on_message, on_disconnect
        try:
            client.connect(host, port, 30)
            client.loop_start()
            deadline = time.monotonic() + 10
            while not ready.wait(.05):
                if failed.is_set() or time.monotonic() >= deadline:
                    raise RuntimeError('未收到成功 SUBACK: ' + '; '.join(errors))
            print('READY subscribed', flush=True)
            deadline = time.monotonic() + seconds
            while time.monotonic() < deadline:
                if failed.wait(min(.1, max(0, deadline-time.monotonic()))):
                    raise RuntimeError('; '.join(errors))
        finally:
            client.disconnect()
            client.loop_stop()
            summary = {'evidenceType': 'mqtt_subscriber_capture', 'host': host, 'port': port,
                       'topics': topics, 'seconds': seconds, 'counts': dict(counts),
                       'startedUtc': started, 'finishedUtc': datetime.now(timezone.utc).isoformat(),
                       'subackReceived': ready.is_set(), 'errors': errors}
            with meta.open('x', encoding='utf-8') as m:
                json.dump(summary, m, ensure_ascii=False, indent=2)
    print(json.dumps(summary, ensure_ascii=False), flush=True)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=1883)
    parser.add_argument('--seconds', type=float, default=70)
    parser.add_argument('--output', required=True)
    parser.add_argument('--ais-topic', default='usv/AisMessage')
    args = parser.parse_args()
    try:
        if not 1 <= args.port <= 65535:
            raise ValueError('port 超出范围')
        capture(args.host, args.port, args.seconds, args.output, args.ais_topic)
    except (ValueError, RuntimeError, OSError) as exc:
        parser.exit(2, f'错误: {exc}\n')


if __name__ == '__main__':
    main()
