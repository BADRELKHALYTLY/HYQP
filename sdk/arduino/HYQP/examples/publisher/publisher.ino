/*
 * HYQP Publisher Example — Publishes temperature readings to the broker.
 * Works on ESP32, ESP8266 (NodeMCU), or any Arduino with WiFi.
 *
 * Scenarios demonstrated:
 *   1. Topic creation with JSON Schema
 *   2. QoS 0 publishing (fire-and-forget)
 *   3. QoS 1 publishing (at-least-once)
 *   4. RETAIN publishing (stored for future subscribers)
 *   5. Wildcard publishing (sensors/#)
 */

#include <WiFi.h>      // ESP32 — use <ESP8266WiFi.h> for NodeMCU
#include <HYQP.h>

// ── Configuration ────────────────────────────────────────────────────
const char* WIFI_SSID     = "YOUR_WIFI_SSID";
const char* WIFI_PASSWORD = "YOUR_WIFI_PASSWORD";
const char* BROKER_HOST   = "192.168.1.100";  // HYQP broker IP
const int   BROKER_PORT   = 4444;

HYQPPublisher pub(BROKER_HOST, BROKER_PORT, "esp32-temp-sensor");

// ── JSON Schema for the temperature topic ────────────────────────────
const char* TEMP_SCHEMA =
    "{\"type\":\"object\",\"required\":[\"value\"],"
    "\"properties\":{\"value\":{\"type\":\"number\",\"minimum\":-50,\"maximum\":150},"
    "\"unit\":{\"type\":\"string\",\"enum\":[\"celsius\",\"fahrenheit\"]}}}";

unsigned long lastPublish = 0;
int publishCount = 0;

void setup() {
    Serial.begin(115200);
    delay(1000);
    Serial.println("\n=== HYQP Publisher Example ===\n");

    // Connect to Wi-Fi
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    Serial.print("Connecting to WiFi");
    while (WiFi.status() != WL_CONNECTED) {
        delay(500);
        Serial.print(".");
    }
    Serial.printf("\nConnected! IP: %s\n", WiFi.localIP().toString().c_str());

    // Connect to HYQP broker
    if (pub.connect()) {
        Serial.println("Connected to HYQP broker");
    } else {
        Serial.println("Failed to connect to broker!");
        return;
    }

    // Scenario 1: Create topic with JSON Schema
    String resp = pub.createTopic("sensors/temp", TEMP_SCHEMA);
    Serial.printf("Create topic: %s\n", resp.c_str());

    // Create additional topics for wildcard demo
    pub.createTopic("sensors/humidity");
    pub.createTopic("sensors/pressure");
}

void loop() {
    pub.loop();

    if (!pub.isConnected()) return;

    if (millis() - lastPublish < 2000) return;  // publish every 2s
    lastPublish = millis();

    // Simulate temperature reading
    float temp = 20.0 + random(0, 300) / 10.0;  // 20.0 - 50.0
    char payload[64];
    snprintf(payload, sizeof(payload),
             "{\"value\":%.1f,\"unit\":\"celsius\"}", temp);

    if (publishCount < 10) {
        // Scenario 2: QoS 0 (fire-and-forget)
        String resp = pub.publish("sensors/temp", payload);
        Serial.printf("[QoS 0] Published temp=%.1f -> %s\n", temp, resp.c_str());
    }
    else if (publishCount == 10) {
        // Scenario 3: QoS 1 (at-least-once)
        String resp = pub.publish("sensors/temp", payload, 1);
        Serial.printf("[QoS 1] Published temp=%.1f -> %s\n", temp, resp.c_str());
    }
    else if (publishCount == 11) {
        // Scenario 4: RETAIN (stored for future subscribers)
        String resp = pub.publish("sensors/temp", payload, 0, true);
        Serial.printf("[RETAIN] Published temp=%.1f -> %s\n", temp, resp.c_str());
    }
    else if (publishCount == 12) {
        // Scenario 5: Wildcard publish to all sensor topics
        String resp = pub.publish("sensors/#", "{\"alert\":\"system check\"}");
        Serial.printf("[Wildcard] Published to sensors/# -> %s\n", resp.c_str());
    }
    else if (publishCount == 13) {
        Serial.println("\nAll scenarios complete. Disconnecting...");
        pub.disconnect();
    }

    publishCount++;
}
