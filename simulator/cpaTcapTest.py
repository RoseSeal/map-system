import argparse
import csv
import io
import json
import time

import paho.mqtt.client as mqtt


TOPIC = "usv/AisMessage"
OWN_MMSI = "123456789"

# 字段顺序与用户给定格式保持一致
FIELDS = [
	"ID",
	"MSGTIME",
	"MMSI",
	"LON",
	"LAT",
	"SpeedOverGround",
	"CourseOverGround",
	"NavigationalStatus",
	"RateOfTurn",
	"TrueHeading",
	"TimeStamp",
	"PositionAccuracy",
	"RAIMFlag",
	"SpecialManoeuvre",
	"SlotTimeOut",
	"SyncState",
	"MessageType",
	"PORT",
	"NMEA",
]


def to_csv_line(row: dict) -> str:
	"""Convert a dict row to one CSV line with ordered fields."""
	buf = io.StringIO()
	writer = csv.writer(buf, quoting=csv.QUOTE_ALL)
	writer.writerow([row.get(field, "") for field in FIELDS])
	return buf.getvalue().strip("\r\n")


def to_ais_json(row: dict) -> str:
	"""Match ais_simulator.py payload keys to avoid parser mismatch."""
	data = {
		"MSGTIME": row.get("MSGTIME", ""),
		"MMSI": row.get("MMSI", ""),
		"LAT": row.get("LAT", ""),
		"LON": row.get("LON", ""),
		"SOG": row.get("SpeedOverGround", ""),
		"COG": row.get("CourseOverGround", ""),
		"HEADING": row.get("TrueHeading", ""),
	}
	return json.dumps(data, ensure_ascii=False)


def build_row(
	msg_id: int,
	msg_time: str,
	mmsi: str,
	lon: str,
	lat: str,
	sog: str,
	cog: str,
	nav_status: str,
	rot: str,
	heading: str,
	port: str = "",
	nmea: str = "SX3",
) -> dict:
	row = {field: "" for field in FIELDS}
	row.update(
		{
			"ID": str(msg_id),
			"MSGTIME": msg_time,
			"MMSI": str(mmsi),
			"LON": str(lon),
			"LAT": str(lat),
			"SpeedOverGround": str(sog),
			"CourseOverGround": str(cog),
			"NavigationalStatus": str(nav_status),
			"RateOfTurn": str(rot),
			"TrueHeading": str(heading),
			"PORT": str(port),
			"NMEA": str(nmea),
		}
	)
	return row


def build_test_cases() -> list:
	"""
	每个测试用例至少包含一条本船和一条目标船消息。
	本船 MMSI 固定为 OWN_MMSI，其余 MMSI 均为目标船。
	你可以根据系统输出的 CPA/TCAP 结果判断计算逻辑是否符合预期。
	"""
	return [
		{
			"name": "case_01_given_sample",
			"expected": "基线样例，验证消息解析/入库/计算链路",
			"rows": [
				build_row(128924488, "2022-5-20 15:27:50", OWN_MMSI, "114.216525", "30.578373", "0", "360", "255", "0", "0", nmea="SX3"),
				build_row(128924489, "2022-5-20 15:27:50", "111000111", "114.144375", "30.596863", "0", "360", "1", "0", "0", nmea="SX3"),
			],
		},
		{
			"name": "case_02_head_on",
			"expected": "对遇场景，预期 CPA 较小且 TCAP 为正",
			"rows": [
				build_row(128924490, "2022-5-20 15:28:00", OWN_MMSI, "114.200000", "30.580000", "12.0", "90", "0", "0", "90", nmea="SX3"),
				build_row(128924491, "2022-5-20 15:28:00", "900001222", "114.240000", "30.580000", "12.0", "270", "0", "0", "270", nmea="SX3"),
			],
		},
		{
			"name": "case_03_crossing",
			"expected": "交叉相遇场景，预期有明显最近会遇时刻",
			"rows": [
				build_row(128924492, "2022-5-20 15:28:10", OWN_MMSI, "114.180000", "30.560000", "10.0", "45", "0", "0", "45", nmea="SX3"),
				build_row(128924493, "2022-5-20 15:28:10", "900002222", "114.220000", "30.600000", "10.0", "225", "0", "0", "225", nmea="SX3"),
			],
		},
		{
			"name": "case_04_overtaking",
			"expected": "追越场景，预期 CPA 小，TCAP 取决于追越速度差",
			"rows": [
				build_row(128924494, "2022-5-20 15:28:20", OWN_MMSI, "114.300000", "30.620000", "6.0", "180", "0", "0", "180", nmea="SX3"),
				build_row(128924495, "2022-5-20 15:28:20", "900003222", "114.300000", "30.626000", "12.0", "180", "0", "0", "180", nmea="SX3"),
			],
		},
		{
			"name": "case_05_parallel_safe",
			"expected": "近平行同向场景，预期 CPA 较大，TCAP 不敏感",
			"rows": [
				build_row(128924496, "2022-5-20 15:28:30", OWN_MMSI, "114.360000", "30.640000", "10.0", "90", "0", "0", "90", nmea="SX3"),
				build_row(128924497, "2022-5-20 15:28:30", "900004222", "114.360000", "30.650000", "10.0", "90", "0", "0", "90", nmea="SX3"),
			],
		},
		{
			"name": "case_06_diverging",
			"expected": "背离场景，预期 TCAP 接近 0 或为负，CPA 随时间增大",
			"rows": [
				build_row(128924498, "2022-5-20 15:28:40", OWN_MMSI, "114.260000", "30.540000", "11.0", "45", "0", "0", "45", nmea="SX3"),
				build_row(128924499, "2022-5-20 15:28:40", "900005222", "114.265000", "30.545000", "11.0", "225", "0", "0", "225", nmea="SX3"),
			],
		},
		{
			"name": "case_07_multi_target_dense",
			"expected": "单本船多目标同批次，验证多目标循环计算和最小 CPA 选取",
			"rows": [
				build_row(128924500, "2022-5-20 15:28:50", OWN_MMSI, "114.210000", "30.590000", "9.5", "120", "0", "0", "120", nmea="SX3"),
				build_row(128924501, "2022-5-20 15:28:50", "900006222", "114.235000", "30.605000", "8.5", "250", "0", "0", "250", nmea="SX3"),
				build_row(128924502, "2022-5-20 15:28:50", "900006333", "114.195000", "30.575000", "12.0", "70", "0", "0", "70", nmea="SX3"),
				build_row(128924503, "2022-5-20 15:28:50", "900006444", "114.220000", "30.560000", "7.0", "350", "0", "0", "350", nmea="SX3"),
			],
		},
		{
			"name": "case_08_stationary_target",
			"expected": "目标近似静止，验证低速/静止目标下 CPA/TCPA 稳定性",
			"rows": [
				build_row(128924504, "2022-5-20 15:29:00", OWN_MMSI, "114.150000", "30.610000", "13.0", "270", "0", "0", "270", nmea="SX3"),
				build_row(128924505, "2022-5-20 15:29:00", "900007222", "114.130000", "30.610000", "0.1", "0", "1", "0", "0", nmea="SX3"),
			],
		},
	]


def publish_case(
	client: mqtt.Client,
	case: dict,
	topic: str,
	interval_sec: float,
	payload_format: str,
) -> None:
	if payload_format == "csv":
		# CSV 模式下先发标题行
		header = to_csv_line({field: field for field in FIELDS})
		client.publish(topic, header)

	print(f"\nPublishing {case['name']}")
	print(f"Expected: {case['expected']}")
	print(f"Topic   : {topic}")
	print(f"Format  : {payload_format}")

	for row in case["rows"]:
		payload = to_ais_json(row) if payload_format == "json" else to_csv_line(row)
		client.publish(topic, payload)
		print(payload)
		time.sleep(interval_sec)


def main() -> None:
	parser = argparse.ArgumentParser(description="Publish CPA/TCAP test cases to MQTT")
	parser.add_argument("--host", default="localhost", help="MQTT broker host")
	parser.add_argument("--port", type=int, default=1883, help="MQTT broker port")
	parser.add_argument("--topic", default=TOPIC, help="MQTT topic")
	parser.add_argument(
		"--format",
		choices=["json", "csv"],
		default="json",
		help="Payload format. Use json to match ais_simulator.py",
	)
	parser.add_argument(
		"--interval",
		type=float,
		default=0.3,
		help="Interval between messages (seconds)",
	)
	args = parser.parse_args()

	client = mqtt.Client()
	client.connect(args.host, args.port, 60)

	for case in build_test_cases():
		publish_case(client, case, args.topic, args.interval, args.format)
		time.sleep(0.5)

	print("\nAll test cases published.")


if __name__ == "__main__":
	main()
