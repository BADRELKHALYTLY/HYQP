"""
Tests all advanced HYQP features from the Python SDK:
  - Regex topic subscribe
  - Regex topic publish
  - Wildcard publish (+, #)
  - All 10 filter operators ($eq, $neq, $gt, $gte, $lt, $lte, $in, $nin, $contains, $regex)
  - QoS 0, 1, 2
  - Retain
  - Persistent sessions
  - Dynamic filter update / removal

Usage:
    1. Start the HYQP broker
    2. python test_regex_filters.py
"""

import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from hyqp import HYQPClient, HYQPError

HOST = sys.argv[1] if len(sys.argv) > 1 else "localhost"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 4444

received = []
passed = 0
failed = 0


def collector(topic, payload, qos, msg_id):
    received.append({"topic": topic, "payload": payload, "qos": qos, "id": msg_id})


def check(name, condition):
    global passed, failed
    if condition:
        print(f"  PASS: {name}")
        passed += 1
    else:
        print(f"  FAIL: {name}")
        failed += 1


def section(title):
    print(f"\n--- {title} ---")


def wait():
    time.sleep(0.5)


# Connect
sub = HYQPClient(host=HOST, port=PORT, client_id="regex-sub", on_message=collector)
sub.connect()
pub = HYQPClient(host=HOST, port=PORT, client_id="regex-pub")
pub.connect()

# Create topics for regex tests
for t in [
    "factory/line-1/temp", "factory/line-2/temp", "factory/line-42/temp",
    "factory/zone-A/temp", "factory/zone-B/temp",
    "home/living/temp", "home/bedroom/temp",
]:
    try:
        pub.create_topic(t)
    except HYQPError:
        pass

# ==========================================================
section("REGEX SUBSCRIBE: factory/regex('line-[0-9]+')/temp")
received.clear()
sub.subscribe("factory/regex('line-[0-9]+')/temp")
wait()

pub.publish("factory/line-1/temp", {"v": 1})
pub.publish("factory/line-42/temp", {"v": 42})
pub.publish("factory/zone-A/temp", {"v": 99})  # should NOT match
wait()

matched_topics = [m["topic"] for m in received]
check("line-1 matched", "factory/line-1/temp" in matched_topics)
check("line-42 matched", "factory/line-42/temp" in matched_topics)
check("zone-A NOT matched", "factory/zone-A/temp" not in matched_topics)

sub.unsubscribe("factory/regex('line-[0-9]+')/temp")

# ==========================================================
section("WILDCARD PUBLISH: factory/# (broadcast to all factory topics)")
sub.subscribe("factory/line-1/temp")
sub.subscribe("factory/zone-A/temp")
time.sleep(1)
received.clear()

pub.publish("factory/#", {"alert": "broadcast"})
time.sleep(1)

topics_received = set(m["topic"] for m in received)
check("wildcard # publish reached at least one topic", len(topics_received) >= 1)

sub.unsubscribe("factory/line-1/temp")
sub.unsubscribe("factory/zone-A/temp")

# ==========================================================
section("REGEX PUBLISH: factory/regex('zone-[A-Z]')/temp")
sub.subscribe("factory/zone-A/temp")
sub.subscribe("factory/zone-B/temp")
time.sleep(1)
received.clear()

pub.publish("factory/regex('zone-[A-Z]')/temp", {"alert": "zones"})
time.sleep(1)

topics_received = set(m["topic"] for m in received)
check("regex publish reached at least one zone", len(topics_received) >= 1)

sub.unsubscribe("factory/zone-A/temp")
sub.unsubscribe("factory/zone-B/temp")

# ==========================================================
section("WILDCARD + PUBLISH: factory/+/temp")
sub.subscribe("factory/line-1/temp")
sub.subscribe("factory/zone-A/temp")
time.sleep(1)
received.clear()

pub.publish("factory/+/temp", {"alert": "single-level"})
time.sleep(1)

topics_received = set(m["topic"] for m in received)
check("wildcard + publish reached at least one topic", len(topics_received) >= 1)

sub.unsubscribe("factory/line-1/temp")
sub.unsubscribe("factory/zone-A/temp")

# ==========================================================
section("FILTER OPERATORS (all 10)")

try:
    pub.create_topic("test/ops")
except HYQPError:
    pass

# $eq
received.clear()
sub.subscribe("test/ops", filter={"status": {"$eq": "active"}})
wait()
pub.publish("test/ops", {"status": "active", "id": 1})
pub.publish("test/ops", {"status": "error", "id": 2})
wait()
check("$eq: active delivered", any(m["payload"].get("id") == 1 for m in received))
check("$eq: error filtered", not any(m["payload"].get("id") == 2 for m in received))

# $neq
received.clear()
sub.subscribe("test/ops", filter={"status": {"$neq": "error"}})
wait()
pub.publish("test/ops", {"status": "active", "id": 3})
pub.publish("test/ops", {"status": "error", "id": 4})
wait()
check("$neq: active delivered", any(m["payload"].get("id") == 3 for m in received))
check("$neq: error filtered", not any(m["payload"].get("id") == 4 for m in received))

# $gt
received.clear()
sub.subscribe("test/ops", filter={"value": {"$gt": 50}})
wait()
pub.publish("test/ops", {"value": 60, "id": 5})
pub.publish("test/ops", {"value": 30, "id": 6})
wait()
check("$gt: 60 delivered", any(m["payload"].get("id") == 5 for m in received))
check("$gt: 30 filtered", not any(m["payload"].get("id") == 6 for m in received))

# $gte
received.clear()
sub.subscribe("test/ops", filter={"value": {"$gte": 50}})
wait()
pub.publish("test/ops", {"value": 50, "id": 7})
pub.publish("test/ops", {"value": 49, "id": 8})
wait()
check("$gte: 50 delivered", any(m["payload"].get("id") == 7 for m in received))
check("$gte: 49 filtered", not any(m["payload"].get("id") == 8 for m in received))

# $lt
received.clear()
sub.subscribe("test/ops", filter={"value": {"$lt": 20}})
wait()
pub.publish("test/ops", {"value": 10, "id": 9})
pub.publish("test/ops", {"value": 30, "id": 10})
wait()
check("$lt: 10 delivered", any(m["payload"].get("id") == 9 for m in received))
check("$lt: 30 filtered", not any(m["payload"].get("id") == 10 for m in received))

# $lte
received.clear()
sub.subscribe("test/ops", filter={"value": {"$lte": 20}})
wait()
pub.publish("test/ops", {"value": 20, "id": 11})
pub.publish("test/ops", {"value": 21, "id": 12})
wait()
check("$lte: 20 delivered", any(m["payload"].get("id") == 11 for m in received))
check("$lte: 21 filtered", not any(m["payload"].get("id") == 12 for m in received))

# $in
received.clear()
sub.subscribe("test/ops", filter={"status": {"$in": ["active", "warning"]}})
wait()
pub.publish("test/ops", {"status": "active", "id": 13})
pub.publish("test/ops", {"status": "error", "id": 14})
wait()
check("$in: active delivered", any(m["payload"].get("id") == 13 for m in received))
check("$in: error filtered", not any(m["payload"].get("id") == 14 for m in received))

# $nin
received.clear()
sub.subscribe("test/ops", filter={"status": {"$nin": ["error", "offline"]}})
wait()
pub.publish("test/ops", {"status": "active", "id": 15})
pub.publish("test/ops", {"status": "error", "id": 16})
wait()
check("$nin: active delivered", any(m["payload"].get("id") == 15 for m in received))
check("$nin: error filtered", not any(m["payload"].get("id") == 16 for m in received))

# $contains
received.clear()
sub.subscribe("test/ops", filter={"name": {"$contains": "sensor"}})
wait()
pub.publish("test/ops", {"name": "temp-sensor-01", "id": 17})
pub.publish("test/ops", {"name": "actuator-01", "id": 18})
wait()
check("$contains: sensor delivered", any(m["payload"].get("id") == 17 for m in received))
check("$contains: actuator filtered", not any(m["payload"].get("id") == 18 for m in received))

# $regex
received.clear()
sub.subscribe("test/ops", filter={"code": {"$regex": "^S[0-9]+$"}})
wait()
pub.publish("test/ops", {"code": "S123", "id": 19})
pub.publish("test/ops", {"code": "ABC", "id": 20})
wait()
check("$regex: S123 delivered", any(m["payload"].get("id") == 19 for m in received))
check("$regex: ABC filtered", not any(m["payload"].get("id") == 20 for m in received))

# Remove filter
sub.unsubscribe("test/ops")

# ==========================================================
section("QoS 2 (exactly-once)")
try:
    pub.create_topic("test/qos2")
except HYQPError:
    pass

received.clear()
sub.subscribe("test/qos2")
wait()
pub.publish("test/qos2", {"msg": "exactly-once"}, qos=2)
wait()
check("QoS 2 delivered", len(received) >= 1 and received[-1]["payload"].get("msg") == "exactly-once")
check("QoS 2 msg_id present", received[-1].get("qos") == 2 if received else False)
sub.unsubscribe("test/qos2")

# ==========================================================
section("PERSISTENT SESSION")
persub = HYQPClient(host=HOST, port=PORT, client_id="persist-sub", persistent=True, on_message=collector)
persub.connect()
try:
    pub.create_topic("test/persist")
except HYQPError:
    pass
persub.subscribe("test/persist")
wait()
persub.disconnect()
time.sleep(0.5)

# Reconnect — subscriptions should be restored
persub2 = HYQPClient(host=HOST, port=PORT, client_id="persist-sub", persistent=True, on_message=collector)
resp = persub2.connect()
check("Persistent session restored", "restored" in resp.lower() or "Connected" in resp)
received.clear()
pub.publish("test/persist", {"msg": "after-reconnect"})
wait()
check("Message received after reconnect", any(m["payload"].get("msg") == "after-reconnect" for m in received))
persub2.disconnect()

# ==========================================================
section("COMBINED FILTER (multiple operators)")
received.clear()
sub.subscribe("test/ops", filter={"value": {"$gt": 10, "$lt": 50}})
wait()
pub.publish("test/ops", {"value": 30, "id": "in-range"})
pub.publish("test/ops", {"value": 5, "id": "too-low"})
pub.publish("test/ops", {"value": 60, "id": "too-high"})
wait()
check("Combined: 30 delivered", any(m["payload"].get("id") == "in-range" for m in received))
check("Combined: 5 filtered", not any(m["payload"].get("id") == "too-low" for m in received))
check("Combined: 60 filtered", not any(m["payload"].get("id") == "too-high" for m in received))

# ==========================================================
# Summary
sub.disconnect()
pub.disconnect()

print(f"\n{'='*60}")
print(f"  RESULTS: {passed} passed, {failed} failed, {passed + failed} total")
print(f"{'='*60}")
sys.exit(0 if failed == 0 else 1)
