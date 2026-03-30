/*
 * HYQP CPU Temperature Sensor — ESP32
 *
 * Reads the internal temperature sensor of the ESP32 and publishes
 * readings to the HYQP broker every 5 seconds.
 *
 * This is a production-ready example with:
 *   - Auto-reconnect on connection loss
 *   - JSON Schema validation
 *   - QoS 1 for reliable delivery
 *   - RETAIN for latest reading
 */

#include <WiFi.h>
#include <HYQP.h>

// ── Configuration ────────────────────────────────────────────────────
const char* WIFI_SSID     = "YOUR_WIFI_SSID";
const char* WIFI_PASSWORD = "YOUR_WIFI_PASSWORD";
const char* BROKER_HOST   = "192.168.1.100";
const int   BROKER_PORT   = 4444;

const unsigned long PUBLISH_INTERVAL = 5000;  // 5 seconds

HYQPPublisher pub(BROKER_HOST, BROKER_PORT, "esp32-cpu-temp");

unsigned long lastPublish = 0;
bool topicCreated = false;

// ── Read ESP32 internal temperature ──────────────────────────────────
float readCpuTemp() {
#ifdef ESP32
    // ESP32 has an internal temperature sensor
    return temperatureRead();  // Returns degrees Celsius
#else
    // Simulated for other boards
    return 30.0 + random(0, 200) / 10.0;
#endif
}

// ── Wi-Fi connection with retry ──────────────────────────────────────
void connectWiFi() {
    if (WiFi.status() == WL_CONNECTED) return;

    Serial.print("Connecting to WiFi");
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    int retries = 0;
    while (WiFi.status() != WL_CONNECTED && retries < 40) {
        delay(500);
        Serial.print(".");
        retries++;
    }
    if (WiFi.status() == WL_CONNECTED) {
        Serial.printf("\nWiFi connected: %s\n", WiFi.localIP().toString().c_str());
    } else {
        Serial.println("\nWiFi connection failed!");
    }
}

// ── Broker connection with topic creation ────────────────────────────
void connectBroker() {
    if (pub.isConnected()) return;

    Serial.println("Connecting to HYQP broker...");
    if (pub.connect()) {
        Serial.println("Broker connected!");

        // Create topic with schema (only first time or after broker restart)
        const char* schema =
            "{\"type\":\"object\",\"required\":[\"value\"],"
            "\"properties\":{\"value\":{\"type\":\"number\","
            "\"minimum\":0,\"maximum\":120}}}";
        pub.createTopic("esp32/cpu/temp", schema);
        topicCreated = true;
    } else {
        Serial.println("Broker connection failed. Retrying in 5s...");
    }
}

void setup() {
    Serial.begin(115200);
    delay(1000);
    Serial.println("\n=== HYQP ESP32 CPU Temperature Sensor ===\n");

    connectWiFi();
    connectBroker();
}

void loop() {
    pub.loop();

    // Reconnect Wi-Fi if lost
    if (WiFi.status() != WL_CONNECTED) {
        connectWiFi();
        return;
    }

    // Reconnect broker if lost
    if (!pub.isConnected()) {
        connectBroker();
        return;
    }

    // Publish every PUBLISH_INTERVAL ms
    if (millis() - lastPublish >= PUBLISH_INTERVAL) {
        lastPublish = millis();

        float temp = readCpuTemp();
        char payload[64];
        snprintf(payload, sizeof(payload),
                 "{\"value\":%.1f,\"unit\":\"celsius\"}", temp);

        // Publish with QoS 1 (at-least-once) and RETAIN
        String resp = pub.publish("esp32/cpu/temp", payload, 1, true);
        Serial.printf("  Published: %.1f C -> %s\n", temp, resp.c_str());
    }
}
