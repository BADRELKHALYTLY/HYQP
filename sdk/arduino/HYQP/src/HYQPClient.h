/*
 * HYQPClient.h — Full-featured HYQP client for Arduino / ESP32 / NodeMCU.
 *
 * Handles TCP connection, protocol framing, QoS handshakes (0, 1, 2),
 * and callback-based message dispatch.
 *
 * Zero external dependencies beyond the Arduino WiFi stack.
 * Works on ESP32, ESP8266 (NodeMCU), and any Arduino with WiFiClient.
 *
 * Author : Badr El Khalyly
 * Version: 1.0.0
 * License: MIT
 */

#ifndef HYQP_CLIENT_H
#define HYQP_CLIENT_H

#include <Arduino.h>
#include <WiFiClient.h>

// ── Configuration ────────────────────────────────────────────────────
#ifndef HYQP_MAX_TOPIC_CBS
#define HYQP_MAX_TOPIC_CBS 10      // max per-topic callbacks
#endif

#ifndef HYQP_BUF_SIZE
#define HYQP_BUF_SIZE 1024         // TCP read buffer
#endif

#ifndef HYQP_DEFAULT_PORT
#define HYQP_DEFAULT_PORT 4444
#endif

#ifndef HYQP_RESPONSE_TIMEOUT
#define HYQP_RESPONSE_TIMEOUT 5000 // ms
#endif

// ── Callback types ───────────────────────────────────────────────────
// void callback(topic, payload, qos, msgId)
typedef void (*HYQPMessageCallback)(const char* topic, const char* payload,
                                     uint8_t qos, int msgId);
typedef void (*HYQPConnectCallback)(const char* response);
typedef void (*HYQPDisconnectCallback)(const char* reason);

// ── HYQPClient ───────────────────────────────────────────────────────
class HYQPClient {
public:
    /**
     * @param host      Broker hostname or IP
     * @param port      Broker TCP port (default 4444)
     * @param clientId  Unique client identifier
     */
    HYQPClient(const char* host, uint16_t port = HYQP_DEFAULT_PORT,
               const char* clientId = "esp32-hyqp");

    // ── Connection lifecycle ─────────────────────────────────────────
    bool connect(bool persistent = false);
    void disconnect();
    bool reconnect(bool persistent = false);
    bool isConnected();

    // ── Topic management ─────────────────────────────────────────────
    /** CREATE a topic, optionally with a JSON Schema string. */
    String createTopic(const char* topic, const char* schemaJson = nullptr);

    // ── Publish ──────────────────────────────────────────────────────
    /**
     * Publish a message.
     * @param topic    Destination topic (may contain wildcards / regex)
     * @param payload  Message body (JSON string or plain text)
     * @param qos      Quality of service (0, 1, or 2)
     * @param retain   If true, broker stores message for future subscribers
     */
    String publish(const char* topic, const char* payload,
                   uint8_t qos = 0, bool retain = false);

    // ── Subscribe / Unsubscribe ──────────────────────────────────────
    /**
     * Subscribe to a topic.
     * @param topic      Topic filter (supports +, #, regex('...'))
     * @param filterJson Payload filter JSON (e.g. "{\"value\":{\"$gt\":30}}")
     * @param cb         Per-topic callback (overrides default onMessage)
     */
    String subscribe(const char* topic, const char* filterJson = nullptr,
                     HYQPMessageCallback cb = nullptr);

    String unsubscribe(const char* topic);

    // ── Event loop (MUST call in Arduino loop()) ─────────────────────
    void loop();

    // ── Callbacks ────────────────────────────────────────────────────
    void onMessage(HYQPMessageCallback cb);
    void onConnect(HYQPConnectCallback cb);
    void onDisconnect(HYQPDisconnectCallback cb);

    // ── Getters ──────────────────────────────────────────────────────
    const char* getClientId() const { return _clientId; }
    const char* getHost() const { return _host; }
    uint16_t    getPort() const { return _port; }
    const char* lastResponse() const { return _lastResp; }

private:
    // Connection
    WiFiClient _tcp;
    const char* _host;
    uint16_t    _port;
    const char* _clientId;
    bool        _connected;
    bool        _persistent;

    // Buffers
    char _buf[HYQP_BUF_SIZE];
    int  _bufPos;
    char _lastResp[256];

    // Callbacks
    HYQPMessageCallback    _onMessage;
    HYQPConnectCallback    _onConnect;
    HYQPDisconnectCallback _onDisconnect;

    // Per-topic callbacks
    struct TopicCb {
        char topic[64];
        HYQPMessageCallback cb;
    };
    TopicCb _topicCbs[HYQP_MAX_TOPIC_CBS];
    int     _topicCbCount;

    // Internal
    void    _sendRaw(const char* line);
    String  _sendAndWait(const char* cmd, unsigned long timeoutMs = HYQP_RESPONSE_TIMEOUT);
    void    _processLine(const char* line);
    void    _handleMessage(const char* line);
    void    _cleanup(const char* reason);

    HYQPMessageCallback _findCallback(const char* topic);
    bool _topicMatches(const char* pattern, const char* topic);
};

// ── HYQPPublisher (thin facade) ──────────────────────────────────────
class HYQPPublisher {
public:
    HYQPPublisher(const char* host, uint16_t port = HYQP_DEFAULT_PORT,
                  const char* clientId = "esp32-pub");

    bool   connect(bool persistent = false);
    void   disconnect();
    bool   isConnected();
    String createTopic(const char* topic, const char* schemaJson = nullptr);
    String publish(const char* topic, const char* payload,
                   uint8_t qos = 0, bool retain = false);
    void   loop();

private:
    HYQPClient _client;
};

// ── HYQPSubscriber (thin facade) ─────────────────────────────────────
class HYQPSubscriber {
public:
    HYQPSubscriber(const char* host, uint16_t port = HYQP_DEFAULT_PORT,
                   const char* clientId = "esp32-sub");

    bool   connect(bool persistent = false);
    void   disconnect();
    bool   isConnected();
    String subscribe(const char* topic, const char* filterJson = nullptr,
                     HYQPMessageCallback cb = nullptr);
    String unsubscribe(const char* topic);
    void   onMessage(HYQPMessageCallback cb);
    void   onConnect(HYQPConnectCallback cb);
    void   onDisconnect(HYQPDisconnectCallback cb);
    void   loop();

private:
    HYQPClient _client;
};

#endif // HYQP_CLIENT_H
