# HYQP — Hybrid Query Protocol

Lightweight pub/sub messaging broker built with Java 21 virtual threads.

**Key differences with MQTT:**
- Wildcard publishing (`PUBLISH sensors/# ...` broadcasts to all matching topics)
- Regex-based topic filtering (`regex('pattern')` in subscribe and publish)
- Schema enforcement on topics (JSON Schema validation on every publish)
- Payload filtering on subscribe (`$gt`, `$lt`, `$eq`, `$regex`, etc.)
- QoS 0, 1 (at-least-once), and 2 (exactly-once) delivery guarantees
- Retained messages (new subscribers receive the last message immediately)
- Persistent sessions (subscriptions restored on reconnect)
- File-based message and session persistence
- Simple text-based protocol over TCP (no binary framing)

---

## Prerequisites

- **Java 21+** installed and on your PATH

## Build

```bash
cd C:\Users\badre\Documents\HYQP
javac -d target/classes $(find src/main/java -name "*.java")
```

Or from Windows CMD:
```cmd
cd C:\Users\badre\Documents\HYQP
dir /s /b src\main\java\*.java > sources.txt
javac -d target\classes @sources.txt
```

---

## Quick Start

You need **3 terminals**, always in this order:

### Terminal 1 — Start the Broker

```bash
java -cp target/classes com.hyqp.broker.BrokerMain
```

Default port: `4444`. Custom port:

```bash
java -cp target/classes com.hyqp.broker.BrokerMain 5555
```

### Terminal 2 — Start a Subscriber

```bash
java -cp target/classes com.hyqp.broker.client.Subscriber <name> <topic> [filter_json] [host] [port]
```

Examples:

```bash
# No filter — receive all messages
java -cp target/classes com.hyqp.broker.client.Subscriber sub1 sensors/temp

# With payload filter — only receive messages where value > 30
java -cp target/classes com.hyqp.broker.client.Subscriber sub1 sensors/temp '{"value":{"$gt":30}}'
```

> **Important:** Start the subscriber BEFORE the publisher. The broker does not store messages — if no subscriber is listening, the message is lost.

### Terminal 3 — Start a Publisher

```bash
java -cp target/classes com.hyqp.broker.client.Publisher <name> [host] [port]
```

Example:

```bash
java -cp target/classes com.hyqp.broker.client.Publisher sensor-1
```

Then type commands interactively:

```
CREATE sensors/temp
PUBLISH sensors/temp {"value": 22.5}
DISCONNECT
```

### Arguments

| Argument | Description | Default |
|----------|-------------|---------|
| `name` | Client identifier (any string, no spaces) | *required* |
| `topic` | Topic path (e.g. `sensors/temp`) | *required for Subscriber* |
| `filter_json` | Payload filter (JSON, must start with `{`) | *optional, Subscriber only* |
| `host` | Broker hostname | `localhost` |
| `port` | Broker port | `4444` |

---

## Protocol Reference

All messages are single-line, UTF-8, terminated by `\n`.

### Client → Broker

| Command | Syntax | Description |
|---------|--------|-------------|
| CONNECT | `CONNECT <clientName>` | Clean session (state discarded on disconnect) |
| CONNECT | `CONNECT <clientName> PERSISTENT` | Persistent session (subscriptions restored on reconnect) |
| CREATE | `CREATE <topic>` | Create a new topic (no schema) |
| CREATE | `CREATE <topic> <schema_json>` | Create a new topic with JSON Schema validation |
| SUBSCRIBE | `SUBSCRIBE <topic>` | Subscribe to a topic (supports `+`, `#`, `regex('...')`) |
| SUBSCRIBE | `SUBSCRIBE <topic> FILTER <filter_json>` | Subscribe with payload filtering |
| UNSUBSCRIBE | `UNSUBSCRIBE <topic>` | Unsubscribe from a topic |
| PUBLISH | `PUBLISH <topic> <payload>` | Publish QoS 0 (fire-and-forget) |
| PUBLISH | `PUBLISH QOS1 <topic> <payload>` | Publish QoS 1 (at-least-once, requires PUBACK) |
| PUBLISH | `PUBLISH QOS2 <topic> <payload>` | Publish QoS 2 (exactly-once, 4-step handshake) |
| PUBLISH | `PUBLISH RETAIN <topic> <payload>` | Publish retained (stored, sent to future subscribers) |
| PUBLISH | `PUBLISH RETAIN QOS1 <topic> <payload>` | Retained + QoS 1 |
| PUBACK | `PUBACK <messageId>` | Acknowledge QoS 1 message |
| PUBREC | `PUBREC <messageId>` | QoS 2 step 2: message received |
| PUBREL | `PUBREL <messageId>` | QoS 2 step 3: release |
| PUBCOMP | `PUBCOMP <messageId>` | QoS 2 step 4: complete |
| DISCONNECT | `DISCONNECT` | Graceful disconnection |

### Broker → Client

| Response | Meaning |
|----------|---------|
| `OK Connected as <name>` | Clean session accepted |
| `OK Connected as <name> (session restored)` | Persistent session restored |
| `OK Topic created: <topic>` | Topic created without schema |
| `OK Topic created with schema: <topic>` | Topic created with schema |
| `OK Subscribed to <topic>` | Subscription confirmed |
| `OK Subscribed to <topic> with filter` | Subscription with payload filter confirmed |
| `OK Filter updated on <topic>` | Payload filter changed on existing subscription |
| `OK Filter removed on <topic>` | Payload filter removed from existing subscription |
| `OK Unsubscribed from <topic>` | Unsubscription confirmed |
| `OK Published to <topic>` | Message accepted and dispatched |
| `OK Goodbye` | Disconnection acknowledged |
| `MESSAGE <topic> <payload>` | Incoming message for a subscribed topic |
| `ERROR <message>` | Error (bad command, schema violation, etc.) |

---

## Wildcards

HYQP supports MQTT-style wildcards for both **subscribing** and **publishing**.

### `+` — Single-level wildcard

Matches exactly **one** level in the topic hierarchy.

```
SUBSCRIBE sensors/+          matches: sensors/temp, sensors/humidity
                              no match: sensors/temp/indoor

SUBSCRIBE +/temp/+           matches: home/temp/living, office/temp/server
                              no match: home/temp
```

### `#` — Multi-level wildcard

Matches **zero or more** levels. Must be the last token.

```
SUBSCRIBE sensors/#          matches: sensors
                                       sensors/temp
                                       sensors/temp/indoor
                                       sensors/a/b/c/d

SUBSCRIBE #                  matches everything
```

### `regex('...')` — Regex-level filter (HYQP-specific)

Matches exactly **one** level using a Java regex pattern. Can be used in any position.

```
SUBSCRIBE factory/regex('line-[0-9]+')/temperature
    matches: factory/line-1/temperature
             factory/line-42/temperature
    no match: factory/zone-A/temperature

SUBSCRIBE regex('sensor-[a-z]+')/+/data
    matches: sensor-abc/temp/data
    no match: sensor-123/temp/data
```

You can use **multiple regex levels** in the same topic:

```
SUBSCRIBE regex('building-[0-9]+')/regex('floor-[0-9]+')/temp
    matches: building-5/floor-12/temp
    no match: building-5/lobby/temp
```

Combine freely with `+` and `#`:

```
SUBSCRIBE regex('zone-[A-Z]')/#           → zone-A, zone-B/temp, zone-C/a/b/c
SUBSCRIBE regex('factory-[0-9]+')/+/temp  → factory-1/line-A/temp, factory-99/anything/temp
```

> **Note:** The regex pattern must not contain spaces (use `\s` instead). Invalid regex patterns are rejected at parse time with a clear error message.

### Wildcard & Regex Publishing (HYQP-specific)

Unlike MQTT, HYQP allows wildcards **and regex** in PUBLISH. The message is broadcast to **all existing concrete topics** that match the pattern.

```
PUBLISH sensors/# {"alert": "all sensors"}
    → sent to sensors/temp, sensors/humidity, sensors/temp/indoor, ...

PUBLISH sensors/+ {"alert": "one level"}
    → sent to sensors/temp, sensors/humidity (not sensors/temp/indoor)

PUBLISH factory/regex('line-[0-9]+')/temperature {"alert": "high temp"}
    → sent to factory/line-1/temperature, factory/line-42/temperature
    → NOT sent to factory/zone-A/temperature
```

> The target topics must already exist (via CREATE or a prior SUBSCRIBE) for wildcard/regex publish to reach them.

---

## Payload Filtering

Subscribers can filter messages based on **payload content**. Only messages whose JSON payload matches the filter conditions are delivered. Filtering happens **broker-side** — filtered messages are never sent over the network.

> **Topic filters** (`+`, `#`, `regex('...')`) control **where** you listen.
> **Payload filters** (`$gt`, `$lt`, `$eq`, ...) control **what** you receive.

### Syntax

```
SUBSCRIBE <topic> FILTER <filter_json>
```

The filter is a JSON object where each key is a **field name** in the payload, and the value defines the **conditions** on that field. All conditions are ANDed together.

### Example

```
SUBSCRIBE sensors/temp FILTER {"value":{"$gt":20,"$lt":80},"unit":{"$eq":"celsius"}}
```

This subscriber only receives messages where:
- `value` is greater than 20 **AND** less than 80
- `unit` equals `"celsius"`

| Published payload | Delivered? | Reason |
|---|---|---|
| `{"value":50,"unit":"celsius"}` | **yes** | All conditions met |
| `{"value":10,"unit":"celsius"}` | no | value not > 20 |
| `{"value":50,"unit":"fahrenheit"}` | no | unit is not celsius |
| `{"value":90,"unit":"celsius"}` | no | value not < 80 |

### Supported Operators

| Operator | Description | Value type | Example |
|----------|-------------|-----------|---------|
| `$eq` | Equals | any | `{"unit":{"$eq":"celsius"}}` |
| `$neq` | Not equals | any | `{"status":{"$neq":"error"}}` |
| `$gt` | Greater than | number | `{"value":{"$gt":30}}` |
| `$gte` | Greater than or equal | number | `{"value":{"$gte":30}}` |
| `$lt` | Less than | number | `{"value":{"$lt":80}}` |
| `$lte` | Less than or equal | number | `{"value":{"$lte":80}}` |
| `$in` | Value in list | array | `{"status":{"$in":["active","warning"]}}` |
| `$nin` | Value NOT in list | array | `{"status":{"$nin":["error","offline"]}}` |
| `$contains` | String contains | string | `{"name":{"$contains":"sensor"}}` |
| `$regex` | String matches regex | string | `{"id":{"$regex":"^S[0-9]+$"}}` |

Operators can be **combined** on the same field (all are ANDed):

```
SUBSCRIBE sensors/temp FILTER {"value":{"$gte":0,"$lte":100}}
```

### Dynamic Filter Management

A subscriber can **change** or **remove** its filter at any time by re-sending a SUBSCRIBE on the same topic. No need to unsubscribe first.

**Add a filter:**
```
SUBSCRIBE sensors/temp FILTER {"value":{"$gt":30}}
→ OK Subscribed to sensors/temp with filter
```

**Change the filter:**
```
SUBSCRIBE sensors/temp FILTER {"value":{"$lt":10}}
→ OK Filter updated on sensors/temp
```

**Remove the filter (receive all messages again):**
```
SUBSCRIBE sensors/temp
→ OK Filter removed on sensors/temp
```

### CLI Subscriber with Filter

From the command line, pass the filter JSON as the third argument:

```bash
# Only receive temperatures above 30
java -cp target/classes com.hyqp.broker.client.Subscriber sub1 sensors/temp '{"value":{"$gt":30}}'

# Only receive active or warning statuses
java -cp target/classes com.hyqp.broker.client.Subscriber sub1 alerts '{"status":{"$in":["active","warning"]}}'

# Combine with topic wildcards and payload filter
java -cp target/classes com.hyqp.broker.client.Subscriber sub1 sensors/+ '{"value":{"$gte":0,"$lte":50}}'
```

### Filter Examples

**Range filter:**
```json
{"value": {"$gt": 20, "$lt": 80}}
```

**Exact match on multiple fields:**
```json
{"unit": {"$eq": "celsius"}, "location": {"$eq": "indoor"}}
```

**String pattern:**
```json
{"sensorId": {"$regex": "^TEMP-[0-9]{4}$"}}
```

**Exclusion filter:**
```json
{"status": {"$nin": ["error", "offline"]}, "value": {"$gte": 0}}
```

**Shorthand — direct value equals (without $eq):**
```json
{"unit": "celsius"}
```
This is equivalent to `{"unit": {"$eq": "celsius"}}`.

### Schema vs Filter — What's the difference?

| | Schema (CREATE) | Filter (SUBSCRIBE) |
|---|---|---|
| **Who defines it** | Topic creator | Each subscriber individually |
| **Applies to** | All publishers on the topic | Only the subscriber who set it |
| **Effect on rejected messages** | Publisher gets `ERROR`, message not dispatched | Message is dispatched to other subscribers, just not to this one |
| **Purpose** | Data governance — enforce payload structure | Data interest — receive only relevant messages |
| **Can be changed** | No (set at topic creation) | Yes (re-SUBSCRIBE at any time) |

---

## Quality of Service (QoS)

HYQP supports three QoS levels, matching MQTT's delivery guarantee model.

### QoS 0 — Fire-and-forget (default)

No acknowledgment. The message is delivered at most once. This is the default when no QoS is specified.

```
PUBLISH sensors/temp {"value": 22.5}
```

### QoS 1 — At-least-once

The broker delivers the message and expects a `PUBACK` from each subscriber. If no PUBACK is received within 10 seconds, the broker retransmits (up to 3 retries).

```
Publisher:   PUBLISH QOS1 sensors/temp {"value": 22.5}
Broker:      OK Published to sensors/temp

Broker → Sub: MESSAGE 42 QOS1 sensors/temp {"value": 22.5}
Sub → Broker: PUBACK 42
```

The subscriber may receive the message more than once if the PUBACK is lost.

### QoS 2 — Exactly-once

Four-step handshake ensures the message is delivered exactly once with no duplicates.

```
Publisher:   PUBLISH QOS2 sensors/temp {"value": 22.5}
Broker:      OK Published to sensors/temp

Step 1: Broker → Sub: MESSAGE 42 QOS2 sensors/temp {"value": 22.5}
Step 2: Sub → Broker: PUBREC 42      (received)
Step 3: Broker → Sub: PUBREL 42      (release)
Step 4: Sub → Broker: PUBCOMP 42     (complete)
```

### QoS can be combined with RETAIN

```
PUBLISH RETAIN QOS1 sensors/temp {"value": 22.5}
```

---

## Retained Messages

A retained message is stored by the broker and immediately delivered to any **new subscriber** that subscribes to a matching topic, even if the message was published before the subscriber connected.

### Publishing a retained message

```
PUBLISH RETAIN sensors/temp {"value": 22.5}
```

### How it works

1. Publisher sends `PUBLISH RETAIN sensors/temp {"value": 22.5}`
2. Broker stores this as the retained message for `sensors/temp`
3. Broker also dispatches to current subscribers (normal behavior)
4. Later, a new subscriber connects and subscribes to `sensors/temp`
5. The broker immediately sends the retained message to the new subscriber

### Clearing a retained message

Publish a retained message with an empty payload:

```
PUBLISH RETAIN sensors/temp
```

### Retention with wildcards

When a subscriber uses wildcard topics, retained messages from all matching concrete topics are delivered:

```
SUBSCRIBE sensors/#
→ receives retained messages for sensors/temp, sensors/humidity, etc.
```

### Persistence

Retained messages are persisted to disk (`data/retained/`) and survive broker restarts.

---

## Persistent Sessions

By default, client sessions are **clean** — all subscriptions and state are discarded on disconnect. With persistent sessions, the broker saves the client's subscriptions and restores them on reconnection.

### Connecting with a persistent session

```
CONNECT myClient PERSISTENT
```

### How it works

1. Client connects with `CONNECT myClient PERSISTENT`
2. Client subscribes to topics (with or without filters)
3. Client disconnects
4. Client reconnects with `CONNECT myClient PERSISTENT`
5. Broker restores all previous subscriptions automatically
6. Response: `OK Connected as myClient (session restored)`

### Clean session (default)

```
CONNECT myClient
```

A clean session discards all stored state on disconnect. If a stored persistent session exists for this client, it is also cleared.

### Persistence

Session state is persisted to disk (`data/sessions/`) and survives broker restarts.

---

## Topic Schemas

When creating a topic, you can attach a **JSON Schema** that all publishers must respect. Any PUBLISH to that topic with an invalid payload is rejected with an error.

### Creating a topic with a schema

```
CREATE <topic> <schema_json>
```

The schema must be a single-line JSON object. It follows a subset of [JSON Schema](https://json-schema.org/) conventions.

### Example

```
CREATE sensors/temp {"type":"object","required":["value"],"properties":{"value":{"type":"number","minimum":-50,"maximum":100},"unit":{"type":"string","enum":["celsius","fahrenheit"]}}}
```

This defines a topic `sensors/temp` where every payload must:
- Be a JSON **object**
- Have a **required** field `value` of type `number`, between `-50` and `100`
- Optionally have a field `unit` of type `string`, restricted to `"celsius"` or `"fahrenheit"`

### Valid payloads

```
PUBLISH sensors/temp {"value": 22.5, "unit": "celsius"}     → OK
PUBLISH sensors/temp {"value": 0}                            → OK
PUBLISH sensors/temp {"value": -30, "unit": "fahrenheit"}    → OK
```

### Rejected payloads

```
PUBLISH sensors/temp {"unit": "celsius"}
→ ERROR Schema validation failed: $: missing required field 'value'

PUBLISH sensors/temp {"value": 150}
→ ERROR Schema validation failed: $.value: value 150.0 exceeds maximum 100.0

PUBLISH sensors/temp {"value": "hot"}
→ ERROR Schema validation failed: $.value: expected type 'number' but got 'string'

PUBLISH sensors/temp {"value": 22, "unit": "kelvin"}
→ ERROR Schema validation failed: $.unit: value "kelvin" is not in enum ["celsius", "fahrenheit"]
```

### Supported Schema Constraints

| Constraint | Applies to | Example | Description |
|------------|-----------|---------|-------------|
| `type` | any | `"number"` | Expected type: `object`, `array`, `string`, `number`, `integer`, `boolean` |
| `required` | object | `["value","unit"]` | List of mandatory fields |
| `properties` | object | `{"value": {...}}` | Per-field schema (recursive) |
| `minimum` | number | `0` | Minimum numeric value (inclusive) |
| `maximum` | number | `100` | Maximum numeric value (inclusive) |
| `minLength` | string | `1` | Minimum string length |
| `maxLength` | string | `50` | Maximum string length |
| `enum` | any | `["a","b"]` | List of allowed values |
| `items` | array | `{"type":"number"}` | Schema applied to each element in the array |

### Schema examples

**Simple string field:**
```json
{"type": "string", "minLength": 1, "maxLength": 255}
```

**Integer with range:**
```json
{"type": "integer", "minimum": 0, "maximum": 1000}
```

**Object with nested validation:**
```json
{
  "type": "object",
  "required": ["lat", "lon"],
  "properties": {
    "lat": {"type": "number", "minimum": -90, "maximum": 90},
    "lon": {"type": "number", "minimum": -180, "maximum": 180},
    "alt": {"type": "number", "minimum": 0}
  }
}
```

**Array of numbers:**
```json
{
  "type": "object",
  "required": ["readings"],
  "properties": {
    "readings": {
      "type": "array",
      "items": {"type": "number", "minimum": 0}
    }
  }
}
```

**Enum field:**
```json
{
  "type": "object",
  "required": ["status"],
  "properties": {
    "status": {"type": "string", "enum": ["active", "inactive", "maintenance"]}
  }
}
```

> **Note:** A topic created without a schema (`CREATE sensors/temp`) accepts any payload. A topic created via SUBSCRIBE (without prior CREATE) also has no schema.

---

## Full Session Example

```
Terminal 1 — Broker:
$ java -cp target/classes com.hyqp.broker.BrokerMain
HYQP Broker listening on port 4444

Terminal 2 — Admin (create topic with schema):
$ java -cp target/classes com.hyqp.broker.client.Publisher admin
> CREATE sensors/temp {"type":"object","required":["value"],"properties":{"value":{"type":"number","minimum":-50,"maximum":100}}}
  << OK Topic created with schema: sensors/temp
> DISCONNECT

Terminal 3 — Subscriber (all messages):
$ java -cp target/classes com.hyqp.broker.client.Subscriber sub-all sensors/temp
[sub-all] Listening on topic: sensors/temp

Terminal 4 — Subscriber (only hot temps, with payload filter):
$ java -cp target/classes com.hyqp.broker.client.Subscriber sub-hot sensors/temp '{"value":{"$gt":50}}'
[sub-hot] Listening on topic: sensors/temp
[sub-hot] Filter: {"value":{"$gt":50}}

Terminal 5 — Publisher:
$ java -cp target/classes com.hyqp.broker.client.Publisher sensor-1
> PUBLISH sensors/temp {"value": 22.5}
  << OK Published to sensors/temp
     → sub-all receives it, sub-hot does NOT (22.5 < 50)

> PUBLISH sensors/temp {"value": 75}
  << OK Published to sensors/temp
     → sub-all receives it, sub-hot receives it too (75 > 50)

> PUBLISH sensors/temp {"value": 200}
  << ERROR Schema validation failed: $.value: value 200.0 exceeds maximum 100.0
     → nobody receives it (schema rejected)

> DISCONNECT
```

---

## Architecture

```
com.hyqp.broker/
├── BrokerMain.java              Entry point
├── server/
│   ├── Broker.java              TCP accept loop (1 virtual thread per client)
│   └── ClientSession.java       Per-client lifecycle, QoS, session recovery
├── protocol/
│   ├── CommandType.java         All commands including PUBACK, PUBREC, PUBREL, PUBCOMP
│   ├── Command.java             Parsed command with QoS, retain, persistent flags
│   ├── ProtocolParser.java      Line → Command parser
│   └── ProtocolException.java   Malformed input error
├── topic/
│   ├── TopicRegistry.java       Subscription index + schema storage
│   └── TopicMatcher.java        Wildcard + regex matching logic (#, +, regex('...'))
├── dispatch/
│   ├── MessageDispatcher.java   QoS-aware fan-out with filter chain
│   └── MessageFilter.java       Extensibility interface for filtering
├── qos/
│   ├── QosLevel.java            Enum: QOS0, QOS1, QOS2
│   ├── PendingMessage.java      Tracks unacknowledged messages
│   └── QosManager.java          Message ID generation, retry logic, ack handling
├── persistence/
│   ├── RetainedStore.java       Stores retained messages (memory + disk)
│   └── SessionStore.java        Stores persistent sessions (memory + disk)
├── schema/
│   ├── JsonParser.java          Zero-dependency JSON parser
│   ├── JsonValue.java           JSON value representation
│   ├── JsonParseException.java  Parse error
│   ├── SchemaValidator.java     Validates payloads against schemas
│   └── PayloadFilter.java       Evaluates subscriber payload filters
├── benchmark/
│   ├── BenchmarkRunner.java     HYQP-only benchmark suite
│   ├── ComparativeBenchmark.java HYQP vs MQTT vs AMQP benchmark
│   ├── BenchmarkClient.java     HYQP benchmark client
│   ├── MqttBenchmarkClient.java MQTT benchmark client (Paho)
│   ├── AmqpBenchmarkClient.java AMQP benchmark client (RabbitMQ)
│   ├── MetricsCollector.java    Latency/throughput/loss metrics
│   └── BenchmarkResult.java     Result record with CSV/console export
├── client/
│   ├── Publisher.java           Interactive CLI publisher
│   ├── Subscriber.java          CLI subscriber with optional filter
│   └── TestClient.java          Raw command client (debugging)
└── data/
    ├── retained/                Persisted retained messages
    └── sessions/                Persisted session state
```
