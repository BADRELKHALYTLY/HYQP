#!/bin/bash
# =============================================================================
# HYQP - Comparative Benchmark: HYQP vs MQTT vs AMQP
# Prerequisites: Mosquitto on port 1883, RabbitMQ on port 5672
# Run from project root: bash scripts/run-benchmark.sh [runs]
# =============================================================================

RUNS=${1:-3}
echo "HYQP Comparative Benchmark — $RUNS runs per scenario"
echo "Compiling..."
cd "$(dirname "$0")/.."
javac -cp "target/classes;lib/*" -d target/classes $(find src/main/java -name "*.java") 2>&1
echo "Running benchmark..."
java -cp "target/classes;lib/*" com.hyqp.broker.benchmark.ComparativeBenchmark $RUNS
echo "Results in results/ directory"
