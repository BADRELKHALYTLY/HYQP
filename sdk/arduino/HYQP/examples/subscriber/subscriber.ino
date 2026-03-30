/*
 * HYQP Subscriber Example — Listens for temperature readings.
 * Works on ESP32, ESP8266 (NodeMCU), or any Arduino with WiFi.
 *
 * Scenarios demonstrated:
 *   1. Per-topic callbacks
 *   2. Payload filtering (only value > 25)
 *   3. Wildcard subscription (sensors/#)
 *   4. Persistent session
 *   5. Lifecycle callbacks (onConnect, onDisconnect)
 */

#include <WiFi.h>      // ESP32 — use <ESP8266WiFi.h> for NodeMCU
#include <HYQP.h>

// ── Configuration ────────────────────────────────────────────────────
const char* WIFI_SSID     = "YOUR_WIFI_SSID";
const char* WIFI_PASSWORD = "YOUR_WIFI_PASSWORD";
const char* BROKER_HOST   = "192.168.1.100";
const int   BROKER_PORT   = 4444;

HYQPSubscriber sub(BROKER_HOST, BROKER_PORT, "esp32-dashboard");

// ── Scenario 1: Per-topic callback for temperature ───────────────────
void onTemp(const char* topic, const char* payload,
            uint8_t qos, int msgId) {
    Serial.printf("  TEMP [%s]: %s", topic, payload);
    if (qos > 0) Serial.printf("  (QoS %d, id=%d)", qos, msgId);
    Serial.println();
}

// ── Default callback (catch-all) ─────────────────────────────────────
void onAny(const char* topic, const char* payload,
           uint8_t qos, int msgId) {
    Serial.printf("  [ANY] %s: %s\n", topic, payload);
}

// ── Scenario 5: Lifecycle callbacks ──────────────────────────────────
void onConnected(const char* response) {
    Serial.printf("CONNECTED: %s\n", response);
}

void onDisconnected(const char* reason) {
    Serial.printf("DISCONNECTED: %s\n", reason);
}

void setup() {
    Serial.begin(115200);
    delay(1000);
    Serial.println("\n=== HYQP Subscriber Example ===\n");

    // Connect to Wi-Fi
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    Serial.print("Connecting to WiFi");
    while (WiFi.status() != WL_CONNECTED) {
        delay(500);
        Serial.print(".");
    }
    Serial.printf("\nConnected! IP: %s\n", WiFi.localIP().toString().c_str());

    // Register lifecycle callbacks
    sub.onMessage(onAny);
    sub.onConnect(onConnected);
    sub.onDisconnect(onDisconnected);

    // Scenario 4: Connect with persistent session
    if (!sub.connect(true)) {  // persistent = true
        Serial.println("Failed to connect to broker!");
        return;
    }

    // Scenario 1 + 2: Subscribe with per-topic callback and filter
    String resp = sub.subscribe(
        "sensors/temp",
        "{\"value\":{\"$gt\":25}}",  // Payload filter: only value > 25
        onTemp                        // Per-topic callback
    );
    Serial.printf("Subscribed to sensors/temp (value > 25): %s\n", resp.c_str());

    // Scenario 3: Wildcard subscription (uses default onAny callback)
    resp = sub.subscribe("sensors/#");
    Serial.printf("Subscribed to sensors/# (catch-all): %s\n", resp.c_str());

    Serial.println("\nWaiting for messages...\n");
}

void loop() {
    sub.loop();

    // Auto-reconnect if connection lost
    if (!sub.isConnected()) {
        Serial.println("Connection lost. Reconnecting in 5s...");
        delay(5000);
        if (sub.connect(true)) {
            sub.subscribe("sensors/temp", "{\"value\":{\"$gt\":25}}", onTemp);
            sub.subscribe("sensors/#");
        }
    }
}
