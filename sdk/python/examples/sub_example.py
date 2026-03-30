"""
Subscriber example — listens for temperature readings on the HYQP broker.
Works on Raspberry Pi, desktop, or any Python 3.8+ environment.

Usage:
    python sub_example.py [broker_host] [broker_port]
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from hyqp import Subscriber


HOST = sys.argv[1] if len(sys.argv) > 1 else "localhost"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 4444


def on_temp(topic, payload, qos, msg_id):
    """Called when a temperature message arrives."""
    val = payload.get("value", "?") if isinstance(payload, dict) else payload
    unit = payload.get("unit", "") if isinstance(payload, dict) else ""
    qos_tag = f" [QoS {qos}]" if qos else ""
    print(f"  {topic}: {val} {unit}{qos_tag}")


def on_any(topic, payload, qos, msg_id):
    """Catch-all for messages that don't match a specific callback."""
    print(f"  [ANY] {topic}: {payload}")


def on_connect(client, response):
    print(f"Connected: {response}")


def on_disconnect(client, reason):
    print(f"Disconnected: {reason}")


sub = Subscriber(
    host=HOST,
    port=PORT,
    client_id="rpi-dashboard",
    persistent=True,
    on_message=on_any,
    on_connect=on_connect,
    on_disconnect=on_disconnect,
)

sub.connect()

# Subscribe to temperature with a filter: only values > 25
sub.subscribe(
    "sensors/temp",
    callback=on_temp,
    filter={"value": {"$gt": 25}},
)
print("Subscribed to sensors/temp (value > 25)")

# Subscribe to all sensors (wildcard)
sub.subscribe("sensors/#")
print("Subscribed to sensors/# (catch-all)")

print("Waiting for messages (Ctrl-C to quit)...\n")
sub.loop_forever()
