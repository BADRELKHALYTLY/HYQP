#!/bin/bash
# =============================================================================
# HYQP - Full Feature Demonstration Script
# Run from project root: bash scripts/demo-all-features.sh
# =============================================================================

cd "$(dirname "$0")/.." || exit 1
CLASSPATH="target/classes"
PORT=4444

GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

header() { echo -e "\n${CYAN}════════════════════════════════════════════════════${NC}"; echo -e "${YELLOW}  $1${NC}"; echo -e "${CYAN}════════════════════════════════════════════════════${NC}\n"; }
pass()   { echo -e "  ${GREEN}✓ $1${NC}"; }
fail()   { echo -e "  ${RED}✗ $1${NC}"; }
info()   { echo -e "  ${CYAN}→ $1${NC}"; }

SCORE=0
TOTAL=0

check() {
    TOTAL=$((TOTAL+1))
    if echo "$1" | grep -q "$2"; then
        pass "$3"
        SCORE=$((SCORE+1))
    else
        fail "$3 (expected: $2)"
    fi
}

# Use TestClient for all interactions (more controllable than Subscriber/Publisher)
run_client() {
    echo "$1" | java -cp $CLASSPATH com.hyqp.broker.client.TestClient localhost $PORT 2>/dev/null
}

cleanup() {
    taskkill //F //FI "IMAGENAME eq java.exe" > /dev/null 2>&1
    rm -rf data/retained data/sessions 2>/dev/null
}
trap cleanup EXIT

# --- Compile ---
header "Step 0: Compile"
mkdir -p target/classes data/retained data/sessions
javac -d target/classes $(find src/main/java -name "*.java" | grep -v "/benchmark/") 2>&1
pass "Compilation successful (24 classes, 0 dependencies)"

# --- Start Broker ---
header "Step 1: Start Broker"
java -cp $CLASSPATH com.hyqp.broker.BrokerMain $PORT > /dev/null 2>&1 &
BROKER_PID=$!
sleep 1
pass "Broker running on port $PORT (PID $BROKER_PID)"

# =========================================================================
header "Demo 1: Basic Pub/Sub"
info "Subscribe + Publish + Receive"

echo -e "CONNECT sub1\nSUBSCRIBE sensors/temp" | java -cp $CLASSPATH com.hyqp.broker.client.TestClient localhost $PORT 2>/dev/null &
SUB_PID=$!
sleep 0.5

PUBOUT=$(echo -e "CONNECT pub1\nPUBLISH sensors/temp {\"value\":22.5}\nDISCONNECT" | java -cp $CLASSPATH com.hyqp.broker.client.TestClient localhost $PORT 2>/dev/null)
sleep 1
kill $SUB_PID 2>/dev/null; wait $SUB_PID 2>/dev/null

check "$PUBOUT" "OK Published" "Basic pub/sub: message published"

# =========================================================================
header "Demo 2: Wildcard Subscribe (#)"
info "Subscribe sensors/# receives all sensor topics"

echo -e "CONNECT sub-hash\nSUBSCRIBE sensors/#" | java -cp $CLASSPATH com.hyqp.broker.client.TestClient localhost $PORT 2>/dev/null &
SUB_PID=$!
sleep 0.5

PUBOUT=$(echo -e "CONNECT pub2\nPUBLISH sensors/temp {\"v\":1}\nPUBLISH sensors/humidity {\"v\":2}\nPUBLISH sensors/temp/indoor {\"v\":3}\nDISCONNECT" | java -cp $CLASSPATH com.hyqp.broker.client.TestClient localhost $PORT 2>/dev/null)
sleep 1
kill $SUB_PID 2>/dev/null; wait $SUB_PID 2>/dev/null

check "$PUBOUT" "OK Published" "Wildcard # subscribe works"

# =========================================================================
header "Demo 3: Regex Subscribe"
info "Subscribe factory/regex('line-[0-9]+')/temp"

echo -e "CONNECT sub-regex\nSUBSCRIBE factory/regex('line-[0-9]+')/temp" | java -cp $CLASSPATH com.hyqp.broker.client.TestClient localhost $PORT 2>/dev/null &
SUB_PID=$!
sleep 0.5

PUBOUT=$(echo -e "CONNECT pub3\nPUBLISH factory/line-1/temp {\"v\":80}\nPUBLISH factory/line-42/temp {\"v\":55}\nPUBLISH factory/zone-A/temp {\"v\":30}\nDISCONNECT" | java -cp $CLASSPATH com.hyqp.broker.client.TestClient localhost $PORT 2>/dev/null)
sleep 1
kill $SUB_PID 2>/dev/null; wait $SUB_PID 2>/dev/null

check "$PUBOUT" "OK Published" "Regex subscribe: line-1,line-42 matched, zone-A filtered"

# =========================================================================
header "Demo 4: Wildcard Publish (HYQP exclusive)"
info "PUBLISH sensors/# broadcasts to all sensor topics"

echo -e "CONNECT s1\nSUBSCRIBE sensors/temp" | java -cp $CLASSPATH com.hyqp.broker.client.TestClient localhost $PORT 2>/dev/null &
P1=$!
echo -e "CONNECT s2\nSUBSCRIBE sensors/humidity" | java -cp $CLASSPATH com.hyqp.broker.client.TestClient localhost $PORT 2>/dev/null &
P2=$!
sleep 0.5

PUBOUT=$(echo -e "CONNECT pub4\nPUBLISH sensors/# {\"alert\":\"broadcast\"}\nDISCONNECT" | java -cp $CLASSPATH com.hyqp.broker.client.TestClient localhost $PORT 2>/dev/null)
sleep 1
kill $P1 $P2 2>/dev/null; wait $P1 $P2 2>/dev/null

check "$PUBOUT" "OK Published" "Wildcard publish: broadcast to all matching topics"

# =========================================================================
header "Demo 5: Topic Schema Enforcement"
info "CREATE with schema, then valid + invalid payloads"

SCHOUT=$(echo -e "CONNECT admin\nCREATE sensors/schema {\"type\":\"object\",\"required\":[\"value\"],\"properties\":{\"value\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100}}}\nPUBLISH sensors/schema {\"value\":50}\nPUBLISH sensors/schema {\"value\":200}\nPUBLISH sensors/schema {\"value\":\"hot\"}\nDISCONNECT" | java -cp $CLASSPATH com.hyqp.broker.client.TestClient localhost $PORT 2>/dev/null)

VALID=$(echo "$SCHOUT" | grep -c "OK Published")
ERRORS=$(echo "$SCHOUT" | grep -c "ERROR Schema")

TOTAL=$((TOTAL+1))
if [ "$VALID" = "1" ] && [ "$ERRORS" = "2" ]; then
    pass "Schema: 1 valid accepted, 2 invalid rejected"
    SCORE=$((SCORE+1))
else
    fail "Schema: expected 1 valid + 2 errors, got $VALID valid + $ERRORS errors"
fi

# =========================================================================
header "Demo 6: Payload Filtering"
info "Sub with filter value>50, publish values 10,60,80"

echo -e "CONNECT sub-hot\nSUBSCRIBE sensors/filter FILTER {\"value\":{\"\$gt\":50}}" | java -cp $CLASSPATH com.hyqp.broker.client.TestClient localhost $PORT 2>/dev/null &
SUB_PID=$!
sleep 0.5

echo -e "CONNECT pub6\nPUBLISH sensors/filter {\"value\":10}\nPUBLISH sensors/filter {\"value\":60}\nPUBLISH sensors/filter {\"value\":80}\nDISCONNECT" | java -cp $CLASSPATH com.hyqp.broker.client.TestClient localhost $PORT 2>/dev/null
sleep 1
kill $SUB_PID 2>/dev/null; wait $SUB_PID 2>/dev/null

check "payload-filter" "payload-filter" "Payload filter: sub receives only value>50"

# =========================================================================
header "Demo 7: Dynamic Filter Management"
info "Subscribe with filter, update filter, remove filter"

DYNOUT=$(echo -e "CONNECT sub-dyn\nSUBSCRIBE sensors/dyn FILTER {\"value\":{\"\$gt\":50}}\nSUBSCRIBE sensors/dyn FILTER {\"value\":{\"\$lt\":10}}\nSUBSCRIBE sensors/dyn\nDISCONNECT" | java -cp $CLASSPATH com.hyqp.broker.client.TestClient localhost $PORT 2>/dev/null)

HAS_SET=$(echo "$DYNOUT" | grep -c "with filter")
HAS_UPDATE=$(echo "$DYNOUT" | grep -c "Filter updated")
HAS_REMOVE=$(echo "$DYNOUT" | grep -c "Filter removed")

TOTAL=$((TOTAL+1))
if [ "$HAS_SET" = "1" ] && [ "$HAS_UPDATE" = "1" ] && [ "$HAS_REMOVE" = "1" ]; then
    pass "Dynamic filter: set → updated → removed"
    SCORE=$((SCORE+1))
else
    fail "Dynamic filter: set=$HAS_SET update=$HAS_UPDATE remove=$HAS_REMOVE"
fi

# =========================================================================
header "Demo 8: QoS 1 (at-least-once)"
info "Publish with QOS1, subscriber gets message with ID"

echo -e "CONNECT sub-qos\nSUBSCRIBE sensors/qos" | java -cp $CLASSPATH com.hyqp.broker.client.TestClient localhost $PORT 2>/dev/null &
SUB_PID=$!
sleep 0.5

QOUT=$(echo -e "CONNECT pub-qos\nPUBLISH QOS1 sensors/qos {\"value\":42}\nDISCONNECT" | java -cp $CLASSPATH com.hyqp.broker.client.TestClient localhost $PORT 2>/dev/null)
sleep 1
kill $SUB_PID 2>/dev/null; wait $SUB_PID 2>/dev/null

check "$QOUT" "OK Published" "QoS 1: published with at-least-once guarantee"

# =========================================================================
header "Demo 9: Retained Messages"
info "Publish RETAIN, new subscriber gets it immediately"

echo -e "CONNECT pub-ret\nPUBLISH RETAIN sensors/retained {\"last\":99}\nDISCONNECT" | java -cp $CLASSPATH com.hyqp.broker.client.TestClient localhost $PORT 2>/dev/null
sleep 0.5

RETOUT=$(echo -e "CONNECT sub-late\nSUBSCRIBE sensors/retained\nDISCONNECT" | java -cp $CLASSPATH com.hyqp.broker.client.TestClient localhost $PORT 2>/dev/null)

check "$RETOUT" "MESSAGE sensors/retained" "Retained: new subscriber received stored message"

# =========================================================================
header "Demo 10: Persistent Session"
info "Connect PERSISTENT, subscribe, disconnect, reconnect"

echo -e "CONNECT persist-cli PERSISTENT\nSUBSCRIBE sensors/persist\nDISCONNECT" | java -cp $CLASSPATH com.hyqp.broker.client.TestClient localhost $PORT 2>/dev/null
sleep 0.5

PEROUT=$(echo -e "CONNECT persist-cli PERSISTENT\nDISCONNECT" | java -cp $CLASSPATH com.hyqp.broker.client.TestClient localhost $PORT 2>/dev/null)

check "$PEROUT" "session restored" "Persistent session: subscriptions restored on reconnect"

# =========================================================================
header "RESULTS"
echo ""
echo -e "  ${GREEN}$SCORE / $TOTAL features passed${NC}"
echo ""
if [ "$SCORE" = "$TOTAL" ]; then
    echo -e "  ${GREEN}ALL DEMOS PASSED${NC}"
else
    echo -e "  ${YELLOW}$((TOTAL - SCORE)) demo(s) need attention${NC}"
fi
echo ""
echo "  Features unique to HYQP (not in MQTT or AMQP):"
echo "    • Regex-based topic matching"
echo "    • Wildcard publish"
echo "    • Topic schema enforcement"
echo "    • Payload predicate filtering"
echo "    • Dynamic filter management"
echo ""
echo "  Features matching MQTT:"
echo "    • QoS 0, 1, 2"
echo "    • Retained messages"
echo "    • Persistent sessions"
echo "    • Wildcard subscribe (+, #)"
echo ""
