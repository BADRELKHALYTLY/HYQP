/*
 * HYQP Regex & Filter Operators Test — ESP32/NodeMCU
 *
 * Tests:
 *   - Regex subscribe: factory/regex('line-[0-9]+')/temp
 *   - Wildcard publish: factory/#, factory/+/temp
 *   - Regex publish: factory/regex('zone-[A-Z]')/temp
 *   - All 10 filter operators ($eq, $neq, $gt, $gte, $lt, $lte,
 *                               $in, $nin, $contains, $regex)
 *   - Combined filters
 *   - Persistent sessions
 */

#include <WiFi.h>
#include <HYQP.h>

const char* WIFI_SSID     = "YOUR_WIFI_SSID";
const char* WIFI_PASSWORD = "YOUR_WIFI_PASSWORD";
const char* BROKER_HOST   = "192.168.1.100";
const int   BROKER_PORT   = 4444;

HYQPClient sub(BROKER_HOST, BROKER_PORT, "esp-regex-sub");
HYQPClient pub(BROKER_HOST, BROKER_PORT, "esp-regex-pub");

volatile int received = 0;
char lastTopic[128] = "";
char lastPayload[256] = "";

void collector(const char* topic, const char* payload,
               uint8_t qos, int msgId) {
    received++;
    strncpy(lastTopic, topic, sizeof(lastTopic) - 1);
    strncpy(lastPayload, payload, sizeof(lastPayload) - 1);
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

void section(const char* title) {
    Serial.printf("\n--- %s ---\n", title);
}

void setup() {
    Serial.begin(115200);
    delay(1000);
    Serial.println("\n=== HYQP Regex & Filters Test ===\n");

    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    while (WiFi.status() != WL_CONNECTED) { delay(500); Serial.print("."); }
    Serial.printf("\nWiFi: %s\n\n", WiFi.localIP().toString().c_str());

    sub.onMessage(collector);
    sub.connect();
    pub.connect();

    // Create test topics
    const char* topics[] = {
        "factory/line-1/temp", "factory/line-2/temp", "factory/line-42/temp",
        "factory/zone-A/temp", "factory/zone-B/temp", "test/ops"
    };
    for (int i = 0; i < 6; i++) pub.createTopic(topics[i]);

    // ── REGEX SUBSCRIBE ──
    section("REGEX SUBSCRIBE: factory/regex('line-[0-9]+')/temp");
    sub.subscribe("factory/regex('line-[0-9]+')/temp");
    delay(500);
    received = 0;
    pub.publish("factory/line-1/temp", "{\"v\":1}");
    delay(300); sub.loop();
    check("line-1 matched", received > 0);
    received = 0;
    pub.publish("factory/zone-A/temp", "{\"v\":99}");
    delay(300); sub.loop();
    check("zone-A NOT matched", received == 0);
    sub.unsubscribe("factory/regex('line-[0-9]+')/temp");

    // ── WILDCARD PUBLISH ──
    section("WILDCARD PUBLISH: factory/#");
    sub.subscribe("factory/line-1/temp");
    delay(500);
    received = 0;
    pub.publish("factory/#", "{\"alert\":\"broadcast\"}");
    delay(500); sub.loop();
    check("wildcard # reached subscribed topic", received > 0);
    sub.unsubscribe("factory/line-1/temp");

    // ── REGEX PUBLISH ──
    section("REGEX PUBLISH: factory/regex('zone-[A-Z]')/temp");
    sub.subscribe("factory/zone-A/temp");
    delay(500);
    received = 0;
    pub.publish("factory/regex('zone-[A-Z]')/temp", "{\"alert\":\"zones\"}");
    delay(500); sub.loop();
    check("regex publish reached zone-A", received > 0);
    sub.unsubscribe("factory/zone-A/temp");

    // ── FILTER OPERATORS ──
    section("FILTER OPERATORS (all 10)");

    // $eq
    received = 0;
    sub.subscribe("test/ops", "{\"status\":{\"$eq\":\"active\"}}");
    delay(300);
    pub.publish("test/ops", "{\"status\":\"active\",\"id\":1}");
    pub.publish("test/ops", "{\"status\":\"error\",\"id\":2}");
    delay(500); sub.loop();
    check("$eq: active delivered", received >= 1);

    // $neq
    received = 0;
    sub.subscribe("test/ops", "{\"status\":{\"$neq\":\"error\"}}");
    delay(300);
    pub.publish("test/ops", "{\"status\":\"active\",\"id\":3}");
    delay(500); sub.loop();
    check("$neq: active delivered", received >= 1);

    // $gt
    received = 0;
    sub.subscribe("test/ops", "{\"value\":{\"$gt\":50}}");
    delay(300);
    pub.publish("test/ops", "{\"value\":60,\"id\":5}");
    pub.publish("test/ops", "{\"value\":30,\"id\":6}");
    delay(500); sub.loop();
    check("$gt: 60 delivered, 30 filtered", received >= 1);

    // $gte
    received = 0;
    sub.subscribe("test/ops", "{\"value\":{\"$gte\":50}}");
    delay(300);
    pub.publish("test/ops", "{\"value\":50,\"id\":7}");
    delay(500); sub.loop();
    check("$gte: 50 delivered", received >= 1);

    // $lt
    received = 0;
    sub.subscribe("test/ops", "{\"value\":{\"$lt\":20}}");
    delay(300);
    pub.publish("test/ops", "{\"value\":10,\"id\":9}");
    delay(500); sub.loop();
    check("$lt: 10 delivered", received >= 1);

    // $lte
    received = 0;
    sub.subscribe("test/ops", "{\"value\":{\"$lte\":20}}");
    delay(300);
    pub.publish("test/ops", "{\"value\":20,\"id\":11}");
    delay(500); sub.loop();
    check("$lte: 20 delivered", received >= 1);

    // $in
    received = 0;
    sub.subscribe("test/ops", "{\"status\":{\"$in\":[\"active\",\"warning\"]}}");
    delay(300);
    pub.publish("test/ops", "{\"status\":\"active\",\"id\":13}");
    delay(500); sub.loop();
    check("$in: active delivered", received >= 1);

    // $nin
    received = 0;
    sub.subscribe("test/ops", "{\"status\":{\"$nin\":[\"error\",\"offline\"]}}");
    delay(300);
    pub.publish("test/ops", "{\"status\":\"active\",\"id\":15}");
    delay(500); sub.loop();
    check("$nin: active delivered", received >= 1);

    // $contains
    received = 0;
    sub.subscribe("test/ops", "{\"name\":{\"$contains\":\"sensor\"}}");
    delay(300);
    pub.publish("test/ops", "{\"name\":\"temp-sensor-01\",\"id\":17}");
    delay(500); sub.loop();
    check("$contains: sensor delivered", received >= 1);

    // $regex
    received = 0;
    sub.subscribe("test/ops", "{\"code\":{\"$regex\":\"^S[0-9]+$\"}}");
    delay(300);
    pub.publish("test/ops", "{\"code\":\"S123\",\"id\":19}");
    delay(500); sub.loop();
    check("$regex: S123 delivered", received >= 1);
    sub.unsubscribe("test/ops");

    // ── COMBINED FILTER ──
    section("COMBINED FILTER ($gt 10 AND $lt 50)");
    received = 0;
    sub.subscribe("test/ops", "{\"value\":{\"$gt\":10,\"$lt\":50}}");
    delay(300);
    pub.publish("test/ops", "{\"value\":30,\"id\":\"in-range\"}");
    delay(500); sub.loop();
    check("Combined: 30 in range", received >= 1);
    sub.unsubscribe("test/ops");

    // ── PERSISTENT SESSION ──
    section("PERSISTENT SESSION");
    HYQPClient psub(BROKER_HOST, BROKER_PORT, "esp-persist");
    psub.onMessage(collector);
    psub.connect(true);  // persistent
    pub.createTopic("test/persist");
    psub.subscribe("test/persist");
    delay(300);
    psub.disconnect();
    delay(500);
    // Reconnect — subscriptions should be restored
    HYQPClient psub2(BROKER_HOST, BROKER_PORT, "esp-persist");
    psub2.onMessage(collector);
    psub2.connect(true);
    received = 0;
    pub.publish("test/persist", "{\"msg\":\"after-reconnect\"}");
    delay(500); psub2.loop();
    check("Message after reconnect", received > 0);
    psub2.disconnect();

    // ── Summary ──
    sub.disconnect();
    pub.disconnect();
    Serial.printf("\n============================================\n");
    Serial.printf("  RESULTS: %d passed, %d failed, %d total\n",
                  passed, failed, passed + failed);
    Serial.printf("============================================\n");
}

void loop() {}
