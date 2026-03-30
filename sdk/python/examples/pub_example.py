"""
Publisher example — publishes temperature readings to the HYQP broker.
Works on Raspberry Pi, desktop, or any Python 3.8+ environment.

Usage:
    python pub_example.py [broker_host] [broker_port]
"""

import sys
import time
import random
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from hyqp import Publisher

HOST = sys.argv[1] if len(sys.argv) > 1 else "localhost"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 4444

# --- Schema for the temperature topic ---
TEMP_SCHEMA = {
    "type": "object",
    "required": ["value"],
    "properties": {
        "value": {"type": "number", "minimum": -50, "maximum": 150},
        "unit": {"type": "string", "enum": ["celsius", "fahrenheit"]},
    },
}

with Publisher(host=HOST, port=PORT, client_id="rpi-temp-sensor") as pub:
    # Create topic with schema enforcement
    try:
        resp = pub.create_topic("sensors/temp", schema=TEMP_SCHEMA)
        print(f"Topic created: {resp}")
    except Exception as e:
        print(f"Topic may already exist: {e}")

    # Publish 10 readings (QoS 0)
    for i in range(10):
        temp = round(random.uniform(15.0, 45.0), 1)
        payload = {"value": temp, "unit": "celsius"}
        resp = pub.publish("sensors/temp", payload)
        print(f"[QoS 0] Published temp={temp} -> {resp}")
        time.sleep(0.5)

    # Publish with QoS 1 (at-least-once)
    resp = pub.publish("sensors/temp", {"value": 99.9, "unit": "celsius"}, qos=1)
    print(f"[QoS 1] Published -> {resp}")

    # Publish with RETAIN (stored for future subscribers)
    resp = pub.publish(
        "sensors/temp", {"value": 22.0, "unit": "celsius"}, retain=True
    )
    print(f"[RETAIN] Published -> {resp}")

    # Wildcard publish to all sensor topics
    try:
        pub.create_topic("sensors/humidity")
    except Exception:
        pass
    resp = pub.publish("sensors/#", {"alert": "system check"})
    print(f"[Wildcard] Published to sensors/# -> {resp}")

print("Done.")
