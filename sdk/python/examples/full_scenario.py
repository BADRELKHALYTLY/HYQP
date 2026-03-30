"""
Full integration scenario — exercises all HYQP features from Python.
Starts a subscriber and a publisher in the same script.

Usage:
    1. Start the HYQP broker:  java -jar target/hyqp-broker.jar
    2. Run this script:        python full_scenario.py
"""

import sys
import time
import json
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from hyqp import HYQPClient, HYQPError

HOST = sys.argv[1] if len(sys.argv) > 1 else "localhost"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 4444

received = []


def collector(topic, payload, qos, msg_id):
    received.append({"topic": topic, "payload": payload, "qos": qos, "id": msg_id})
    print(f"    <- [{topic}] {payload}  (QoS={qos}, id={msg_id})")


def section(title):
    print(f"\n{'='*60}")
    print(f"  {title}")
    print(f"{'='*60}")


# ---- Subscriber client ----
sub = HYQPClient(host=HOST, port=PORT, client_id="test-sub", on_message=collector)
sub.connect()
print(f"Subscriber connected")

# ---- Publisher client ----
pub = HYQPClient(host=HOST, port=PORT, client_id="test-pub")
pub.connect()
print(f"Publisher connected")

# ==========================================================
section("1. CREATE topic with JSON Schema")
schema = {
    "type": "object",
    "required": ["value"],
    "properties": {
        "value": {"type": "number", "minimum": -50, "maximum": 100},
        "unit": {"type": "string", "enum": ["celsius", "fahrenheit"]},
    },
}
try:
    r = pub.create_topic("test/temp", schema=schema)
    print(f"  -> {r}")
except HYQPError as e:
    print(f"  -> {e} (topic may already exist)")

# ==========================================================
section("2. SUBSCRIBE with payload filter ($gt)")
r = sub.subscribe("test/temp", filter={"value": {"$gt": 30}})
print(f"  -> {r}")
time.sleep(0.3)

# ==========================================================
section("3. PUBLISH — value=22 (should be FILTERED, below 30)")
received.clear()
r = pub.publish("test/temp", {"value": 22, "unit": "celsius"})
print(f"  -> {r}")
time.sleep(0.5)
if not received:
    print("  OK: message was filtered (value 22 < 30)")
else:
    print("  UNEXPECTED: message should have been filtered")

# ==========================================================
section("4. PUBLISH — value=45 (should be DELIVERED)")
received.clear()
r = pub.publish("test/temp", {"value": 45, "unit": "celsius"})
print(f"  -> {r}")
time.sleep(0.5)
if received and received[0]["payload"]["value"] == 45:
    print("  OK: message delivered (value 45 > 30)")
else:
    print("  UNEXPECTED: message should have been delivered")

# ==========================================================
section("5. Schema enforcement — invalid payload")
try:
    r = pub.publish("test/temp", {"value": 150})
    print(f"  UNEXPECTED: should have failed -> {r}")
except HYQPError as e:
    print(f"  OK: schema rejected it -> {e}")

try:
    r = pub.publish("test/temp", {"value": "hot"})
    print(f"  UNEXPECTED: should have failed -> {r}")
except HYQPError as e:
    print(f"  OK: schema rejected it -> {e}")

# ==========================================================
section("6. Dynamic filter update (change to $lt 20)")
r = sub.subscribe("test/temp", filter={"value": {"$lt": 20}})
print(f"  -> {r}")
time.sleep(0.3)

received.clear()
pub.publish("test/temp", {"value": 10, "unit": "celsius"})
time.sleep(0.5)
if received and received[0]["payload"]["value"] == 10:
    print("  OK: new filter works (value 10 < 20)")
else:
    print("  UNEXPECTED: message should have been delivered")

received.clear()
pub.publish("test/temp", {"value": 50, "unit": "celsius"})
time.sleep(0.5)
if not received:
    print("  OK: message filtered (value 50 > 20)")
else:
    print("  UNEXPECTED: message should have been filtered")

# ==========================================================
section("7. Filter removal")
r = sub.subscribe("test/temp")
print(f"  -> {r}")
time.sleep(0.3)

received.clear()
pub.publish("test/temp", {"value": 50, "unit": "celsius"})
time.sleep(0.5)
if received:
    print("  OK: no filter, message delivered")
else:
    print("  UNEXPECTED: message should have been delivered")

# ==========================================================
section("8. QoS 1 (at-least-once)")
received.clear()
r = pub.publish("test/temp", {"value": 33, "unit": "celsius"}, qos=1)
print(f"  -> {r}")
time.sleep(0.5)
if received:
    print(f"  OK: QoS 1 message delivered (id={received[0].get('id')})")

# ==========================================================
section("9. RETAIN — store + new subscriber receives it")
r = pub.publish("test/temp", {"value": 77, "unit": "celsius"}, retain=True)
print(f"  -> {r}")
time.sleep(0.3)

# New subscriber connects and should get the retained message
sub2 = HYQPClient(host=HOST, port=PORT, client_id="test-sub2", on_message=collector)
sub2.connect()
received.clear()
sub2.subscribe("test/temp")
time.sleep(0.5)
if received and received[0]["payload"].get("value") == 77:
    print("  OK: retained message delivered to new subscriber")
else:
    print(f"  Note: retained delivery depends on broker state (got {received})")
sub2.disconnect()

# ==========================================================
section("10. Wildcard subscribe (sensors/+)")
try:
    pub.create_topic("test/humidity")
except HYQPError:
    pass

received.clear()
sub.subscribe("test/+")
time.sleep(0.3)

pub.publish("test/humidity", {"value": 65})
time.sleep(0.5)
if any(m["topic"] == "test/humidity" for m in received):
    print("  OK: wildcard subscription caught test/humidity")

# ==========================================================
section("CLEANUP")
sub.disconnect()
pub.disconnect()
print("All done.")
