import csv
import paho.mqtt.client as mqtt
import json
import time

# MQTT client setup
client = mqtt.Client()
client.connect("localhost", 1883, 60)

with open(
    '/mnt/d/archive_old_projects/Project_backup/data/AIS_data/NBSDC data/ais_data.csv',
    'r'
) as file:
    reader = csv.DictReader(file)
    for row in reader:
        msgTime = row["MSGTIME"]
        mmsi = row["MMSI"]
        lat = row["LAT"]
        lon = row["LON"]
        sog = row["SpeedOverGround"]
        cog = row["CourseOverGround"]
        heading = row["TrueHeading"]
        # Create a message payload        
        data = {
            "MSGTIME": msgTime,
            "MMSI": mmsi,
            "LAT": lat,
            "LON": lon,
            "SOG": sog,
            "COG": cog,
            "HEADING": heading
        }
        payload = json.dumps(data)
        # Publish the message to the MQTT topic
        client.publish("usv/AisMessage", payload)
        print(f"Published data for MMSI: {mmsi}")
        # Sleep for a short time to simulate real-time data streaming
        time.sleep(0.1)


