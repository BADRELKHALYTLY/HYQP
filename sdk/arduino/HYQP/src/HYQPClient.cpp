/*
 * HYQPClient.cpp — Implementation of the HYQP client for Arduino / ESP32.
 *
 * Author : Badr El Khalyly
 * Version: 1.0.0
 */

#include "HYQPClient.h"

// =====================================================================
//  HYQPClient
// =====================================================================

HYQPClient::HYQPClient(const char* host, uint16_t port, const char* clientId)
    : _host(host), _port(port), _clientId(clientId),
      _connected(false), _persistent(false), _bufPos(0),
      _onMessage(nullptr), _onConnect(nullptr), _onDisconnect(nullptr),
      _topicCbCount(0)
{
    memset(_buf, 0, sizeof(_buf));
    memset(_lastResp, 0, sizeof(_lastResp));
}

// ── Connection lifecycle ─────────────────────────────────────────────

bool HYQPClient::connect(bool persistent) {
    _persistent = persistent;
    _bufPos = 0;

    if (!_tcp.connect(_host, _port)) {
        Serial.printf("[HYQP] Failed to connect to %s:%d\n", _host, _port);
        return false;
    }

    // Build CONNECT command
    String cmd = "CONNECT ";
    cmd += _clientId;
    if (_persistent) {
        cmd += " PERSISTENT";
    }

    String resp = _sendAndWait(cmd.c_str());
    if (resp.startsWith("OK")) {
        _connected = true;
        if (_onConnect) {
            _onConnect(resp.c_str());
        }
        return true;
    }

    Serial.printf("[HYQP] Connect failed: %s\n", resp.c_str());
    _tcp.stop();
    return false;
}

void HYQPClient::disconnect() {
    if (_tcp.connected()) {
        _sendAndWait("DISCONNECT");
    }
    _cleanup("client disconnect");
}

bool HYQPClient::reconnect(bool persistent) {
    _cleanup("reconnect");
    return connect(persistent);
}

bool HYQPClient::isConnected() {
    return _connected && _tcp.connected();
}

// ── Topic management ─────────────────────────────────────────────────

String HYQPClient::createTopic(const char* topic, const char* schemaJson) {
    String cmd = "CREATE ";
    cmd += topic;
    if (schemaJson != nullptr) {
        cmd += " ";
        cmd += schemaJson;
    }
    return _sendAndWait(cmd.c_str());
}

// ── Publish ──────────────────────────────────────────────────────────

String HYQPClient::publish(const char* topic, const char* payload,
                           uint8_t qos, bool retain) {
    String cmd = "PUBLISH";
    if (retain) {
        cmd += " RETAIN";
    }
    if (qos > 0) {
        cmd += " QOS";
        cmd += qos;
    }
    cmd += " ";
    cmd += topic;
    cmd += " ";
    cmd += payload;

    return _sendAndWait(cmd.c_str());
}

// ── Subscribe / Unsubscribe ──────────────────────────────────────────

String HYQPClient::subscribe(const char* topic, const char* filterJson,
                              HYQPMessageCallback cb) {
    String cmd = "SUBSCRIBE ";
    cmd += topic;
    if (filterJson != nullptr) {
        cmd += " FILTER ";
        cmd += filterJson;
    }

    // Register per-topic callback
    if (cb != nullptr && _topicCbCount < HYQP_MAX_TOPIC_CBS) {
        // Check if already registered for this topic
        bool found = false;
        for (int i = 0; i < _topicCbCount; i++) {
            if (strcmp(_topicCbs[i].topic, topic) == 0) {
                _topicCbs[i].cb = cb;
                found = true;
                break;
            }
        }
        if (!found) {
            strncpy(_topicCbs[_topicCbCount].topic, topic, 63);
            _topicCbs[_topicCbCount].topic[63] = '\0';
            _topicCbs[_topicCbCount].cb = cb;
            _topicCbCount++;
        }
    }

    return _sendAndWait(cmd.c_str());
}

String HYQPClient::unsubscribe(const char* topic) {
    // Remove per-topic callback
    for (int i = 0; i < _topicCbCount; i++) {
        if (strcmp(_topicCbs[i].topic, topic) == 0) {
            // Shift remaining
            for (int j = i; j < _topicCbCount - 1; j++) {
                _topicCbs[j] = _topicCbs[j + 1];
            }
            _topicCbCount--;
            break;
        }
    }

    String cmd = "UNSUBSCRIBE ";
    cmd += topic;
    return _sendAndWait(cmd.c_str());
}

// ── Event loop ───────────────────────────────────────────────────────

void HYQPClient::loop() {
    if (!_tcp.connected()) {
        if (_connected) {
            _connected = false;
            if (_onDisconnect) {
                _onDisconnect("connection lost");
            }
        }
        return;
    }

    while (_tcp.available()) {
        char c = _tcp.read();
        if (c == '\n') {
            _buf[_bufPos] = '\0';
            // Trim trailing \r
            if (_bufPos > 0 && _buf[_bufPos - 1] == '\r') {
                _buf[_bufPos - 1] = '\0';
            }
            if (_bufPos > 0) {
                _processLine(_buf);
            }
            _bufPos = 0;
        } else if (_bufPos < HYQP_BUF_SIZE - 1) {
            _buf[_bufPos++] = c;
        }
    }
}

// ── Callbacks ────────────────────────────────────────────────────────

void HYQPClient::onMessage(HYQPMessageCallback cb) { _onMessage = cb; }
void HYQPClient::onConnect(HYQPConnectCallback cb) { _onConnect = cb; }
void HYQPClient::onDisconnect(HYQPDisconnectCallback cb) { _onDisconnect = cb; }

// ── Internal: send + wait ────────────────────────────────────────────

void HYQPClient::_sendRaw(const char* line) {
    if (_tcp.connected()) {
        _tcp.print(line);
        _tcp.print('\n');
        _tcp.flush();
    }
}

String HYQPClient::_sendAndWait(const char* cmd, unsigned long timeoutMs) {
    _sendRaw(cmd);

    unsigned long start = millis();
    String response = "";
    char lineBuf[256];
    int linePos = 0;

    while (millis() - start < timeoutMs) {
        if (_tcp.available()) {
            char c = _tcp.read();
            if (c == '\n') {
                lineBuf[linePos] = '\0';
                if (linePos > 0 && lineBuf[linePos - 1] == '\r') {
                    lineBuf[linePos - 1] = '\0';
                }

                // Check if this is a response (OK or ERROR)
                if (strncmp(lineBuf, "OK", 2) == 0 ||
                    strncmp(lineBuf, "ERROR", 5) == 0) {
                    strncpy(_lastResp, lineBuf, sizeof(_lastResp) - 1);
                    return String(lineBuf);
                }

                // It's an async message — process it
                if (linePos > 0) {
                    _processLine(lineBuf);
                }
                linePos = 0;
            } else if (linePos < 255) {
                lineBuf[linePos++] = c;
            }
        }
        yield(); // ESP32/ESP8266 watchdog
    }

    return String("ERROR timeout");
}

// ── Internal: dispatch ───────────────────────────────────────────────

void HYQPClient::_processLine(const char* line) {
    if (strncmp(line, "MESSAGE", 7) == 0) {
        _handleMessage(line);
        return;
    }

    if (strncmp(line, "PUBREL", 6) == 0) {
        // QoS 2 step 3 — respond with PUBCOMP
        const char* p = line + 7; // skip "PUBREL "
        while (*p == ' ') p++;
        String cmd = "PUBCOMP ";
        cmd += p;
        _sendRaw(cmd.c_str());
        return;
    }
}

void HYQPClient::_handleMessage(const char* line) {
    // Formats:
    //   MESSAGE <topic> <payload>                  (QoS 0)
    //   MESSAGE <msgId> QOS1 <topic> <payload>     (QoS 1)
    //   MESSAGE <msgId> QOS2 <topic> <payload>     (QoS 2)

    const char* p = line + 8; // skip "MESSAGE "
    int msgId = -1;
    uint8_t qos = 0;

    // Check if next token is a number (message ID for QoS 1/2)
    if (*p >= '0' && *p <= '9') {
        msgId = atoi(p);
        while (*p && *p != ' ') p++;
        while (*p == ' ') p++;

        // Next should be QOS1 or QOS2
        if (strncmp(p, "QOS", 3) == 0) {
            qos = p[3] - '0';
            p += 4; // skip "QOSn"
            while (*p == ' ') p++;
        }
    }

    // Now p points to "<topic> <payload>"
    // Extract topic (first token)
    char topic[128];
    int ti = 0;
    while (*p && *p != ' ' && ti < 127) {
        topic[ti++] = *p++;
    }
    topic[ti] = '\0';
    while (*p == ' ') p++;

    // Rest is payload
    const char* payload = p;

    // QoS acknowledgments
    if (qos == 1 && msgId >= 0) {
        String ack = "PUBACK ";
        ack += msgId;
        _sendRaw(ack.c_str());
    } else if (qos == 2 && msgId >= 0) {
        String ack = "PUBREC ";
        ack += msgId;
        _sendRaw(ack.c_str());
    }

    // Fire callback
    HYQPMessageCallback cb = _findCallback(topic);
    if (cb) {
        cb(topic, payload, qos, msgId);
    }
}

HYQPMessageCallback HYQPClient::_findCallback(const char* topic) {
    // Exact match first
    for (int i = 0; i < _topicCbCount; i++) {
        if (strcmp(_topicCbs[i].topic, topic) == 0) {
            return _topicCbs[i].cb;
        }
    }

    // Wildcard pattern match
    for (int i = 0; i < _topicCbCount; i++) {
        if (_topicMatches(_topicCbs[i].topic, topic)) {
            return _topicCbs[i].cb;
        }
    }

    // Default
    return _onMessage;
}

bool HYQPClient::_topicMatches(const char* pattern, const char* topic) {
    // Simple wildcard matching for callback routing
    // Supports + (single level) and # (multi level)
    String pat(pattern);
    String top(topic);

    if (pat == top) return true;

    // Split by /
    int pi = 0, ti = 0;
    String patParts[16], topParts[16];
    int patCount = 0, topCount = 0;

    // Parse pattern
    int start = 0;
    for (int i = 0; i <= (int)pat.length(); i++) {
        if (i == (int)pat.length() || pat[i] == '/') {
            if (patCount < 16) {
                patParts[patCount++] = pat.substring(start, i);
            }
            start = i + 1;
        }
    }

    // Parse topic
    start = 0;
    for (int i = 0; i <= (int)top.length(); i++) {
        if (i == (int)top.length() || top[i] == '/') {
            if (topCount < 16) {
                topParts[topCount++] = top.substring(start, i);
            }
            start = i + 1;
        }
    }

    pi = 0; ti = 0;
    while (pi < patCount && ti < topCount) {
        if (patParts[pi] == "#") return true;
        if (patParts[pi] == "+" || patParts[pi] == topParts[ti]) {
            pi++; ti++;
            continue;
        }
        return false;
    }

    return pi == patCount && ti == topCount;
}

void HYQPClient::_cleanup(const char* reason) {
    _connected = false;
    _bufPos = 0;
    if (_tcp.connected()) {
        _tcp.stop();
    }
    if (_onDisconnect && reason) {
        _onDisconnect(reason);
    }
}

// =====================================================================
//  HYQPPublisher
// =====================================================================

HYQPPublisher::HYQPPublisher(const char* host, uint16_t port, const char* clientId)
    : _client(host, port, clientId) {}

bool HYQPPublisher::connect(bool persistent) { return _client.connect(persistent); }
void HYQPPublisher::disconnect() { _client.disconnect(); }
bool HYQPPublisher::isConnected() { return _client.isConnected(); }
String HYQPPublisher::createTopic(const char* topic, const char* schemaJson) {
    return _client.createTopic(topic, schemaJson);
}
String HYQPPublisher::publish(const char* topic, const char* payload,
                              uint8_t qos, bool retain) {
    return _client.publish(topic, payload, qos, retain);
}
void HYQPPublisher::loop() { _client.loop(); }

// =====================================================================
//  HYQPSubscriber
// =====================================================================

HYQPSubscriber::HYQPSubscriber(const char* host, uint16_t port, const char* clientId)
    : _client(host, port, clientId) {}

bool HYQPSubscriber::connect(bool persistent) { return _client.connect(persistent); }
void HYQPSubscriber::disconnect() { _client.disconnect(); }
bool HYQPSubscriber::isConnected() { return _client.isConnected(); }
String HYQPSubscriber::subscribe(const char* topic, const char* filterJson,
                                  HYQPMessageCallback cb) {
    return _client.subscribe(topic, filterJson, cb);
}
String HYQPSubscriber::unsubscribe(const char* topic) { return _client.unsubscribe(topic); }
void HYQPSubscriber::onMessage(HYQPMessageCallback cb) { _client.onMessage(cb); }
void HYQPSubscriber::onConnect(HYQPConnectCallback cb) { _client.onConnect(cb); }
void HYQPSubscriber::onDisconnect(HYQPDisconnectCallback cb) { _client.onDisconnect(cb); }
void HYQPSubscriber::loop() { _client.loop(); }
