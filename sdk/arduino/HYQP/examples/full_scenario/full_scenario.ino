/*
 * HYQP Full Scenario — Exercises all features from a single ESP32.
 * Uses HYQPClient as both publisher and subscriber.
 *
 * Scenarios:
 *   1. CREATE topic with JSON Schema
 *   2. SUBSCRIBE with payload filter ($gt)
 *   3. PUBLISH filtered value (should NOT be delivered)
 *   4. PUBLISH passing value (should be delivered)
 *   5. Schema enforcement (invalid payload rejected)
 *   6. Dynamic filter update ($lt)
 *   7. Filter removal
 *   8. QoS 1 (at-least-once)
 *   9. QoS 2 (exactly-once)
 *  10. RETAIN + new subscriber
 *  11. Wildcard subscribe (test/+)
 *  12. Wildcard publish (test/#)
 */

#include <WiFi.h>
#include <HYQP.h>

const char* WIFI_SSID     = "YOUR_WIFI_SSID";
const char* WIFI_PASSWORD = "YOUR_WIFI_PASSWORD";
const char* BROKER_HOST   = "192.168.1.100";
const int   BROKER_PORT   = 4444;

HYQPClient sub(BROKER_HOST, BROKER_PORT, "esp-test-sub");
HYQPClient pub(BROKER_HOST, BROKER_PORT, "esp-test-pub");

volatile int received = 0;
char lastPayload[256] = "";

void collector(const char* topic, const char* payload,
               uint8_t qos, int msgId) {
    received++;
    strncpy(lastPayload, payload, sizeof(lastPayload) - 1);
    Serial.printf("    <- [%s] %s (QoS=%d, id=%d)\n", topic, payload, qos, msgId);
}

void section(const char* title) {
    Serial.printf("\n============================================\n");
    Serial.printf("  %s\n", title);
    Serial.printf("============================================\n");
}

int passed = 0, failed = 0;
void check(const char* name, bool condition) {
    if (condition) {
        Serial.printf("  PASS: %s\n", name);
        passed++;
    } else {
        Serial.printf("  FAIL: %s\n", name);
        failed++;
    }
}

void setup() {
    Serial.begin(115200);
    delay(1000);
    Serial.println("\n=== HYQP Full Integration Scenario ===\n");

    // Wi-Fi
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    while (WiFi.status() != WL_CONNECTED) { delay(500); Serial.print("."); }
    Serial.printf("\nWiFi connected: %s\n", WiFi.localIP().toString().c_str());

    // Connect both clients
    sub.onMessage(collector);
    sub.connect();
    pub.connect();
    Serial.println("Both clients connected.\n");

    // JSON Schema
    const char* schema =
        "{\"type\":\"object\",\"required\":[\"value\"],"
        "\"properties\":{\"value\":{\"type\":\"number\",\"minimum\":-50,\"maximum\":100},"
        "\"unit\":{\"type\":\"string\",\"enum\":[\"celsius\",\"fahrenheit\"]}}}";

    // ── Scenario 1 ──
    section("1. CREATE topic with JSON Schema");
    String r = pub.createTopic("test/temp", schema);
    Serial.printf("  -> %s\n", r.c_str());

    // ── Scenario 2 ──
    section("2. SUBSCRIBE with filter ($gt 30)");
    r = sub.subscribe("test/temp", "{\"value\":{\"$gt\":30}}");
    Serial.printf("  -> %s\n", r.c_str());
    delay(300);

    // ── Scenario 3 ──
    section("3. PUBLISH value=22 (should be FILTERED)");
    received = 0;
    pub.publish("test/temp", "{\"value\":22,\"unit\":\"celsius\"}");
    delay(500); sub.loop();
    check("value 22 filtered", received == 0);

    // ── Scenario 4 ──
    section("4. PUBLISH value=45 (should be DELIVERED)");
    received = 0;
    pub.publish("test/temp", "{\"value\":45,\"unit\":\"celsius\"}");
    delay(500); sub.loop();
    check("value 45 delivered", received > 0);

    // ── Scenario 5 ──
    section("5. Schema enforcement (invalid payload)");
    r = pub.publish("test/temp", "{\"value\":150}");
    check("value 150 rejected", r.startsWith("ERROR"));
    r = pub.publish("test/temp", "{\"value\":\"hot\"}");
    check("value 'hot' rejected", r.startsWith("ERROR"));

    // ── Scenario 6 ──
    section("6. Dynamic filter update ($lt 20)");
    sub.subscribe("test/temp", "{\"value\":{\"$lt\":20}}");
    delay(300);
    received = 0;
    pub.publish("test/temp", "{\"value\":10,\"unit\":\"celsius\"}");
    delay(500); sub.loop();
    check("value 10 delivered (< 20)", received > 0);
    received = 0;
    pub.publish("test/temp", "{\"value\":50,\"unit\":\"celsius\"}");
    delay(500); sub.loop();
    check("value 50 filtered (> 20)", received == 0);

    // ── Scenario 7 ──
    section("7. Filter removal");
    sub.subscribe("test/temp");
    delay(300);
    received = 0;
    pub.publish("test/temp", "{\"value\":50,\"unit\":\"celsius\"}");
    delay(500); sub.loop();
    check("value 50 delivered (no filter)", received > 0);

    // ── Scenario 8 ──
    section("8. QoS 1 (at-least-once)");
    received = 0;
    r = pub.publish("test/temp", "{\"value\":33,\"unit\":\"celsius\"}", 1);
    delay(500); sub.loop();
    check("QoS 1 published", r.startsWith("OK"));
    check("QoS 1 delivered", received > 0);

    // ── Scenario 9 ──
    section("9. QoS 2 (exactly-once)");
    pub.createTopic("test/qos2");
    sub.subscribe("test/qos2");
    delay(300);
    received = 0;
    r = pub.publish("test/qos2", "{\"msg\":\"exactly-once\"}", 2);
    delay(1000); sub.loop();
    check("QoS 2 published", r.startsWith("OK"));
    check("QoS 2 delivered", received > 0);

    // ── Scenario 10 ──
    section("10. RETAIN message");
    pub.publish("test/temp", "{\"value\":77,\"unit\":\"celsius\"}", 0, true);
    delay(300);
    // New subscriber should receive the retained message
    HYQPClient sub2(BROKER_HOST, BROKER_PORT, "esp-test-sub2");
    sub2.onMessage(collector);
    sub2.connect();
    received = 0;
    sub2.subscribe("test/temp");
    delay(500); sub2.loop();
    check("Retained message delivered", received > 0);
    sub2.disconnect();

    // ── Scenario 11 ──
    section("11. Wildcard subscribe (test/+)");
    pub.createTopic("test/humidity");
    sub.subscribe("test/+");
    delay(300);
    received = 0;
    pub.publish("test/humidity", "{\"value\":65}");
    delay(500); sub.loop();
    check("Wildcard + caught test/humidity", received > 0);

    // ── Scenario 12 ──
    section("12. Wildcard publish (test/#)");
    received = 0;
    pub.publish("test/#", "{\"alert\":\"broadcast\"}");
    delay(500); sub.loop();
    check("Wildcard # publish delivered", received > 0);

    // ── Summary ──
    sub.disconnect();
    pub.disconnect();
    Serial.printf("\n============================================\n");
    Serial.printf("  RESULTS: %d passed, %d failed, %d total\n",
                  passed, failed, passed + failed);
    Serial.printf("============================================\n");
}

void loop() {
    // Nothing — all tests run in setup()
}
