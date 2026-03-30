# HYQP Python SDK

**Lightweight Python client for the HYQP (Hybrid Query Protocol) IoT broker.**

Zero external dependencies — runs on CPython 3.8+, Raspberry Pi OS, and any Linux/Windows/macOS system.

---

## Table of Contents

- [Features](#features)
- [Installation](#installation)
- [Quick Start](#quick-start)
  - [Publisher](#publisher)
  - [Subscriber](#subscriber)
  - [Full Client](#full-client)
- [API Reference](#api-reference)
  - [HYQPClient](#hyqpclient)
  - [Publisher](#publisher-class)
  - [Subscriber](#subscriber-class)
- [Topic Matching](#topic-matching)
  - [Wildcards](#wildcards)
  - [Regex Patterns](#regex-patterns)
  - [Wildcard & Regex Publishing](#wildcard--regex-publishing)
- [Payload Filtering](#payload-filtering)
  - [Filter Operators](#filter-operators)
  - [Combined Filters](#combined-filters)
  - [Dynamic Filter Management](#dynamic-filter-management)
- [JSON Schema Enforcement](#json-schema-enforcement)
- [Quality of Service (QoS)](#quality-of-service-qos)
- [Retained Messages](#retained-messages)
- [Persistent Sessions](#persistent-sessions)
- [Deployment on Raspberry Pi](#deployment-on-raspberry-pi)
  - [Prerequisites](#prerequisites)
  - [Step 1 — Install Raspberry Pi OS](#step-1--install-raspberry-pi-os)
  - [Step 2 — Enable SSH and Connect](#step-2--enable-ssh-and-connect)
  - [Step 3 — Transfer the SDK](#step-3--transfer-the-sdk)
  - [Step 4 — Run the Subscriber](#step-4--run-the-subscriber)
  - [Step 5 — Run the Publisher (sensor example)](#step-5--run-the-publisher-sensor-example)
  - [Step 6 — Auto-start on Boot (systemd)](#step-6--auto-start-on-boot-systemd)
- [Architecture Diagram](#architecture-diagram)
- [Running Tests](#running-tests)

---

## Features

| Feature | Supported |
|---|---|
| CONNECT / DISCONNECT | Yes |
| CREATE topic (with/without JSON Schema) | Yes |
| SUBSCRIBE (with wildcards `+`, `#`) | Yes |
| SUBSCRIBE with regex: `regex('pattern')` | Yes |
| SUBSCRIBE with payload FILTER (10 operators) | Yes |
| Dynamic filter update / removal | Yes |
| PUBLISH (QoS 0 / 1 / 2) | Yes |
| PUBLISH with RETAIN flag | Yes |
| Wildcard PUBLISH (`+`, `#`) | Yes |
| Regex PUBLISH (`regex('pattern')`) | Yes |
| Persistent sessions | Yes |
| Automatic QoS handshake (PUBACK, PUBREC, PUBREL, PUBCOMP) | Yes |
| Callback-based message dispatch | Yes |
| Per-topic and default callbacks | Yes |
| Context manager (`with` statement) | Yes |
| Thread-safe | Yes |
| Zero dependencies | Yes |
| Raspberry Pi compatible | Yes |

---

## Installation

### From the project directory (recommended)

```bash
cd sdk/python
pip install .
```

### Or copy directly (for Raspberry Pi / embedded)

```bash
# Just copy the hyqp/ folder — no pip needed
scp -r sdk/python/hyqp/ pi@raspberrypi:~/hyqp/
```

---

## Quick Start

### Publisher

```python
from hyqp import Publisher

pub = Publisher(host="192.168.1.100", port=4444, client_id="rpi-sensor")
pub.connect()

# Create a topic with schema validation
pub.create_topic("sensors/temp", schema={
    "type": "object",
    "required": ["value"],
    "properties": {
        "value": {"type": "number", "minimum": -50, "maximum": 150},
        "unit":  {"type": "string", "enum": ["celsius", "fahrenheit"]}
    }
})

# Publish a reading
pub.publish("sensors/temp", {"value": 23.5, "unit": "celsius"})

# Publish with QoS 1 (at-least-once delivery)
pub.publish("sensors/temp", {"value": 42.0, "unit": "celsius"}, qos=1)

# Publish with RETAIN (stored for future subscribers)
pub.publish("sensors/temp", {"value": 22.0, "unit": "celsius"}, retain=True)

pub.disconnect()
```

### Subscriber

```python
from hyqp import Subscriber

def on_temp(topic, payload, qos, msg_id):
    print(f"Temperature: {payload['value']} {payload.get('unit', '')}")

sub = Subscriber(host="192.168.1.100", port=4444, client_id="dashboard")
sub.connect()

# Subscribe with a payload filter — only values above 30
sub.subscribe("sensors/temp", callback=on_temp, filter={"value": {"$gt": 30}})

# Block and wait for messages (Ctrl-C to quit)
sub.loop_forever()
```

### Full Client

```python
from hyqp import HYQPClient

client = HYQPClient(host="localhost", port=4444, client_id="my-app")
client.connect()

# Use as both publisher and subscriber
client.subscribe("alerts/#", callback=lambda t, p, q, i: print(f"ALERT: {p}"))
client.publish("sensors/temp", {"value": 55})

client.loop_forever()
```

---

## API Reference

### HYQPClient

```python
HYQPClient(
    host="localhost",       # Broker IP or hostname
    port=4444,              # Broker TCP port
    client_id="hyqp-py",   # Unique client name
    persistent=False,       # Preserve subscriptions across reconnects
    on_message=None,        # Default message callback
    on_connect=None,        # Called after CONNECT succeeds
    on_disconnect=None,     # Called when connection drops
)
```

**Methods:**

| Method | Description |
|---|---|
| `connect(timeout=5.0)` | Open TCP connection, send CONNECT |
| `disconnect()` | Send DISCONNECT, close socket |
| `reconnect(timeout=5.0)` | Close + reconnect |
| `create_topic(topic, schema=None)` | CREATE a topic (optionally with JSON Schema) |
| `publish(topic, payload, qos=0, retain=False)` | Publish a message |
| `subscribe(topic, callback=None, filter=None)` | Subscribe to a topic |
| `unsubscribe(topic)` | Unsubscribe from a topic |
| `loop_forever()` | Block until disconnect or Ctrl-C |
| `stop()` | Signal loop_forever() to exit |
| `is_connected` | Property: True if connected |

**Callback signatures:**

```python
def on_message(topic: str, payload: Any, qos: int, msg_id: int | None) -> None
def on_connect(client: HYQPClient, response: str) -> None
def on_disconnect(client: HYQPClient, reason: str) -> None
```

### Publisher Class

Thin wrapper for publish-only workloads. Same parameters as `HYQPClient`.

```python
Publisher(host, port, client_id, persistent)
```

Methods: `connect()`, `disconnect()`, `create_topic()`, `publish()`.

### Subscriber Class

Thin wrapper for subscribe-only workloads. Same parameters as `HYQPClient`.

```python
Subscriber(host, port, client_id, persistent, on_message, on_connect, on_disconnect)
```

Methods: `connect()`, `disconnect()`, `subscribe()`, `unsubscribe()`, `loop_forever()`, `stop()`.

---

## Topic Matching

### Wildcards

```python
# + matches exactly one level
sub.subscribe("sensors/+")          # matches sensors/temp, sensors/humidity

# # matches zero or more levels (must be last)
sub.subscribe("sensors/#")          # matches sensors/temp, sensors/a/b/c
```

### Regex Patterns

```python
# regex('pattern') matches one level against a Java-compatible regex
sub.subscribe("factory/regex('line-[0-9]+')/temp")
# matches: factory/line-1/temp, factory/line-42/temp
# rejects: factory/zone-A/temp

# Combine regex with wildcards
sub.subscribe("regex('zone-[A-Z]')/+/#")
```

### Wildcard & Regex Publishing

Unlike MQTT, HYQP allows wildcards and regex in PUBLISH commands:

```python
# Broadcast to ALL sensor topics
pub.publish("sensors/#", {"alert": "system check"})

# Publish to all line-N topics via regex
pub.publish("factory/regex('line-[0-9]+')/temp", {"alert": "high temp"})

# Publish to all single-level children
pub.publish("sensors/+", {"status": "ok"})
```

---

## Payload Filtering

### Filter Operators

The broker supports **10 operators** for payload-level filtering:

```python
# Comparison operators
sub.subscribe("t", filter={"value": {"$gt": 30}})       # greater than
sub.subscribe("t", filter={"value": {"$gte": 30}})      # greater or equal
sub.subscribe("t", filter={"value": {"$lt": 80}})       # less than
sub.subscribe("t", filter={"value": {"$lte": 80}})      # less or equal
sub.subscribe("t", filter={"value": {"$eq": 42}})       # equals
sub.subscribe("t", filter={"status": {"$neq": "error"}})# not equals

# Set operators
sub.subscribe("t", filter={"status": {"$in": ["active", "warning"]}})   # in list
sub.subscribe("t", filter={"status": {"$nin": ["error", "offline"]}})   # not in list

# String operators
sub.subscribe("t", filter={"name": {"$contains": "sensor"}})     # substring match
sub.subscribe("t", filter={"code": {"$regex": "^S[0-9]+$"}})     # regex match
```

### Combined Filters

Multiple conditions are combined with AND logic:

```python
# Only deliver if 30 < value < 80
sub.subscribe("sensors/temp", filter={
    "value": {"$gt": 30, "$lt": 80}
})
```

### Dynamic Filter Management

Filters can be updated or removed without disconnecting:

```python
# Initial subscription with filter
sub.subscribe("sensors/temp", filter={"value": {"$gt": 50}})

# Update the filter (replaces the previous one)
sub.subscribe("sensors/temp", filter={"value": {"$lt": 10}})

# Remove the filter (receive all messages)
sub.subscribe("sensors/temp")
```

---

## JSON Schema Enforcement

Attach a JSON Schema when creating a topic. The broker rejects non-conforming payloads **before** they reach any subscriber:

```python
pub.create_topic("sensors/temp", schema={
    "type": "object",
    "required": ["value"],
    "properties": {
        "value": {"type": "number", "minimum": -50, "maximum": 100},
        "unit":  {"type": "string", "enum": ["celsius", "fahrenheit"]}
    }
})

# This succeeds:
pub.publish("sensors/temp", {"value": 22.5, "unit": "celsius"})

# These raise HYQPError:
pub.publish("sensors/temp", {"value": 150})      # exceeds maximum
pub.publish("sensors/temp", {"value": "hot"})     # wrong type
```

---

## Quality of Service (QoS)

| Level | Name | Guarantee | SDK handles automatically |
|---|---|---|---|
| 0 | Fire-and-forget | No acknowledgment | N/A |
| 1 | At-least-once | PUBACK handshake | Yes (auto PUBACK) |
| 2 | Exactly-once | 4-step handshake | Yes (auto PUBREC/PUBCOMP) |

```python
pub.publish("topic", payload, qos=0)   # fire-and-forget
pub.publish("topic", payload, qos=1)   # at-least-once
pub.publish("topic", payload, qos=2)   # exactly-once
```

The SDK automatically responds to QoS handshakes (PUBACK for QoS 1, PUBREC + PUBCOMP for QoS 2).

---

## Retained Messages

A retained message is stored by the broker and delivered to any **future** subscriber:

```python
# Store the latest reading
pub.publish("sensors/temp", {"value": 22.0}, retain=True)

# A new subscriber connecting later will immediately receive this message
sub.subscribe("sensors/temp")  # -> gets {"value": 22.0} right away
```

---

## Persistent Sessions

With persistent sessions, subscriptions survive disconnections:

```python
sub = Subscriber(host="broker", port=4444, client_id="mobile-gw", persistent=True)
sub.connect()
sub.subscribe("sensors/#")
sub.disconnect()

# Later reconnect — subscriptions are restored automatically
sub2 = Subscriber(host="broker", port=4444, client_id="mobile-gw", persistent=True)
sub2.connect()  # -> "OK Connected as mobile-gw (session restored)"
# sensors/# subscription is active again without re-subscribing
```

---

## Deployment on Raspberry Pi

### Prerequisites

- **Raspberry Pi** (any model: Zero W, 3B+, 4, 5)
- **microSD card** (8 GB minimum)
- **Raspberry Pi Imager** installed on your PC
  - Download: https://www.raspberrypi.com/software/

![Raspberry Pi Imager](https://www.raspberrypi.com/app/uploads/2024/03/imager-windows.png)

### Step 1 — Install Raspberry Pi OS

1. Open **Raspberry Pi Imager**
2. Click **"Choose OS"** → select **Raspberry Pi OS (64-bit)** (or Lite for headless)
3. Click **"Choose Storage"** → select your microSD card
4. Click the **gear icon** (Advanced options):
   - Enable SSH (use password or SSH key)
   - Set hostname: `raspberrypi`
   - Set username/password: `pi` / `your_password`
   - Configure Wi-Fi (SSID and password)
5. Click **"Write"** and wait for completion
6. Insert the microSD into the Raspberry Pi and power it on

### Step 2 — Enable SSH and Connect

From your PC, connect via SSH:

```bash
ssh pi@raspberrypi.local
# or use the IP address:
ssh pi@192.168.1.XXX
```

Verify Python is installed (it comes pre-installed on Raspberry Pi OS):

```bash
python3 --version
# Python 3.11.x
```

### Step 3 — Transfer the SDK

**Option A: SCP (from your PC)**

```bash
# From your PC, transfer the SDK folder
scp -r sdk/python/hyqp/ pi@raspberrypi.local:~/hyqp-sdk/hyqp/
scp -r sdk/python/examples/ pi@raspberrypi.local:~/hyqp-sdk/examples/
```

**Option B: Git clone (if the repo is on GitHub)**

```bash
ssh pi@raspberrypi.local
git clone https://github.com/your-repo/HYQP.git
cd HYQP/sdk/python
```

**Option C: USB drive**

1. Copy `sdk/python/` to a USB stick
2. Plug it into the Raspberry Pi
3. Mount and copy:
   ```bash
   sudo mount /dev/sda1 /mnt
   cp -r /mnt/sdk/python ~/hyqp-sdk
   sudo umount /mnt
   ```

### Step 4 — Run the Subscriber

On the Raspberry Pi:

```bash
cd ~/hyqp-sdk
python3 examples/sub_example.py 192.168.1.100 4444
```

Replace `192.168.1.100` with the IP of the machine running the HYQP broker.

Output:

```
Connected: OK Connected as rpi-dashboard
Subscribed to sensors/temp (value > 25)
Subscribed to sensors/# (catch-all)
Waiting for messages (Ctrl-C to quit)...

  sensors/temp: 32.5 celsius
  sensors/temp: 45.1 celsius
```

### Step 5 — Run the Publisher (sensor example)

Create a sensor script on the Raspberry Pi that reads real hardware data:

```python
#!/usr/bin/env python3
"""rpi_sensor.py — reads CPU temperature and publishes it via HYQP."""

import time
from hyqp import Publisher

BROKER = "192.168.1.100"  # your broker IP

def read_cpu_temp():
    with open("/sys/class/thermal/thermal_zone0/temp") as f:
        return round(int(f.read().strip()) / 1000.0, 1)

pub = Publisher(host=BROKER, port=4444, client_id="rpi-cpu-temp")
pub.connect()

try:
    pub.create_topic("rpi/cpu/temp", schema={
        "type": "object",
        "required": ["value"],
        "properties": {
            "value": {"type": "number", "minimum": 0, "maximum": 120}
        }
    })
except Exception:
    pass  # topic may already exist

print("Publishing CPU temperature every 5 seconds...")
try:
    while True:
        temp = read_cpu_temp()
        pub.publish("rpi/cpu/temp", {"value": temp, "unit": "celsius"})
        print(f"  Published: {temp} C")
        time.sleep(5)
except KeyboardInterrupt:
    pub.disconnect()
    print("Stopped.")
```

Run it:

```bash
python3 rpi_sensor.py
```

### Step 6 — Auto-start on Boot (systemd)

Create a systemd service so the publisher starts automatically:

```bash
sudo nano /etc/systemd/system/hyqp-sensor.service
```

Paste:

```ini
[Unit]
Description=HYQP Temperature Sensor
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=pi
WorkingDirectory=/home/pi/hyqp-sdk
ExecStart=/usr/bin/python3 /home/pi/hyqp-sdk/rpi_sensor.py
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Enable and start:

```bash
sudo systemctl daemon-reload
sudo systemctl enable hyqp-sensor.service
sudo systemctl start hyqp-sensor.service

# Check status
sudo systemctl status hyqp-sensor.service

# View logs
journalctl -u hyqp-sensor.service -f
```

The sensor will now start automatically after every reboot.

---

## Architecture Diagram

```
  Raspberry Pi (Publisher)            HYQP Broker (Java 21)         PC / Cloud (Subscriber)
 ┌─────────────────────┐          ┌──────────────────────┐        ┌─────────────────────┐
 │  Python SDK          │   TCP   │  Port 4444            │  TCP   │  Python SDK          │
 │  Publisher            ├────────►│                        │◄───────┤  Subscriber          │
 │                       │        │  ┌────────────────┐   │        │                       │
 │  PUBLISH QOS1         │        │  │ Topic Registry  │   │        │  SUBSCRIBE + FILTER   │
 │  sensors/temp         │        │  │ Schema Validator│   │        │  {"value":{"$gt":30}} │
 │  {"value":23.5}       │        │  │ Payload Filter  │   │        │                       │
 │                       │        │  │ QoS Manager     │   │        │  on_message(t, p, ..) │
 └─────────────────────┘          │  └────────────────┘   │        └─────────────────────┘
                                   │                        │
                                   │  Virtual Threads       │
                                   │  ConcurrentHashMap     │
                                   └──────────────────────┘
```

---

## Running Tests

Start the broker, then run the test suite:

```bash
# Start the HYQP broker (on the broker machine)
java -cp target/classes com.hyqp.broker.BrokerMain

# Run the full test suite (on any machine with Python 3.8+)
cd sdk/python
python examples/full_scenario.py           # 10 integration scenarios
python examples/test_regex_filters.py      # 33 tests: regex, filters, QoS, sessions
```

Expected output:

```
RESULTS: 33 passed, 0 failed, 33 total
```

---

## License

MIT License. Part of the HYQP project.
