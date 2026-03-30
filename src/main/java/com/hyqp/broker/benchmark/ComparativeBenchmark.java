package com.hyqp.broker.benchmark;

import com.hyqp.broker.dispatch.MessageDispatcher;
import com.hyqp.broker.server.Broker;
import com.hyqp.broker.topic.TopicRegistry;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Comparative benchmark: HYQP vs MQTT (Mosquitto) vs AMQP (RabbitMQ).
 *
 * Prerequisites:
 *   - Mosquitto running on localhost:1883
 *   - RabbitMQ running on localhost:5672
 *
 * Usage: java -cp "target/classes;lib/*" com.hyqp.broker.benchmark.ComparativeBenchmark [runs]
 */
public class ComparativeBenchmark {

    private static final int HYQP_PORT = 14444;
    private static final int MQTT_PORT = 1883;
    private static final int AMQP_PORT = 5672;
    private static final String HOST = "localhost";
    private static final int WARMUP = 500;
    private static final int DEFAULT_RUNS = 5;

    private final int runs;
    private final List<BenchmarkResult> allResults = new ArrayList<>();

    public ComparativeBenchmark(int runs) {
        this.runs = runs;
    }

    public static void main(String[] args) throws Exception {
        int runs = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_RUNS;

        System.out.println("============================================================");
        System.out.println("  HYQP Comparative Benchmark");
        System.out.println("  HYQP vs MQTT (Mosquitto) vs AMQP (RabbitMQ)");
        System.out.println("  " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        System.out.println("  Runs per scenario: " + runs);
        System.out.println("============================================================\n");

        ComparativeBenchmark bench = new ComparativeBenchmark(runs);

        // Test connectivity
        // Suppress HYQP broker logs during benchmark
        Logger.getLogger("com.hyqp.broker").setLevel(Level.OFF);

        System.out.println("Checking broker connectivity...");
        boolean mqttOk = bench.checkMqtt();
        boolean amqpOk = bench.checkAmqp();
        System.out.println("  HYQP:  embedded (always available)");
        System.out.println("  MQTT:  " + (mqttOk ? "OK (localhost:" + MQTT_PORT + ")" : "NOT AVAILABLE - skipping"));
        System.out.println("  AMQP:  " + (amqpOk ? "OK (localhost:" + AMQP_PORT + ")" : "NOT AVAILABLE - skipping"));
        System.out.println();

        // Scenario 1: Fan-out
        bench.runForAllProtocols("fan-out-1", 1, 1, 10000, 128, mqttOk, amqpOk);
        bench.runForAllProtocols("fan-out-10", 1, 10, 5000, 128, mqttOk, amqpOk);
        bench.runForAllProtocols("fan-out-50", 1, 50, 2000, 128, mqttOk, amqpOk);
        bench.runForAllProtocols("fan-out-100", 1, 100, 1000, 128, mqttOk, amqpOk);

        // Scenario 2: Fan-in
        bench.runForAllProtocols("fan-in-5", 5, 1, 1000, 128, mqttOk, amqpOk);

        // Scenario 3: Payload size
        bench.runForAllProtocols("payload-64B", 1, 1, 10000, 64, mqttOk, amqpOk);
        bench.runForAllProtocols("payload-1KB", 1, 1, 5000, 1024, mqttOk, amqpOk);
        bench.runForAllProtocols("payload-10KB", 1, 1, 2000, 10240, mqttOk, amqpOk);

        // Scenario 4: Burst
        bench.runForAllProtocols("burst-10k", 1, 1, 10000, 128, mqttOk, amqpOk);

        bench.exportResults();
    }

    private void runForAllProtocols(String scenario, int pubs, int subs, int msgsPerPub,
                                     int payloadSize, boolean mqttOk, boolean amqpOk) throws Exception {
        System.out.println("=== " + scenario + " (" + pubs + " pub, " + subs + " sub, " + msgsPerPub + " msg, " + payloadSize + "B) ===");

        // HYQP
        for (int r = 1; r <= runs; r++) {
            System.out.printf("  HYQP run %d/%d ... ", r, runs);
            BenchmarkResult res = runHyqp(scenario, pubs, subs, msgsPerPub, payloadSize);
            allResults.add(res);
            printShort(res);
        }

        // MQTT
        if (mqttOk) {
            for (int r = 1; r <= runs; r++) {
                System.out.printf("  MQTT run %d/%d ... ", r, runs);
                BenchmarkResult res = runMqtt(scenario, pubs, subs, msgsPerPub, payloadSize);
                allResults.add(res);
                printShort(res);
            }
        }

        // AMQP
        if (amqpOk) {
            for (int r = 1; r <= runs; r++) {
                System.out.printf("  AMQP run %d/%d ... ", r, runs);
                BenchmarkResult res = runAmqp(scenario, pubs, subs, msgsPerPub, payloadSize);
                allResults.add(res);
                printShort(res);
            }
        }
        System.out.println();
    }

    // ======================== HYQP ========================

    private BenchmarkResult runHyqp(String scenario, int numPubs, int numSubs,
                                     int msgsPerPub, int payloadSize) throws Exception {
        TopicRegistry registry = new TopicRegistry();
        MessageDispatcher dispatcher = new MessageDispatcher(registry);
        Broker broker = new Broker(HYQP_PORT, registry, dispatcher);
        Thread bt = Thread.ofVirtual().start(() -> {
            try { broker.start(); } catch (IOException ignored) {}
        });
        Thread.sleep(300);

        MetricsCollector metrics = new MetricsCollector();
        long totalExpected = (long) numPubs * msgsPerPub * numSubs;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicLong count = new AtomicLong();

        List<BenchmarkClient> subscribers = new ArrayList<>();
        for (int s = 0; s < numSubs; s++) {
            BenchmarkClient sub = new BenchmarkClient("hsub-" + s, HOST, HYQP_PORT);
            sub.connect();
            // Fan-out: all subs listen to topic-0. Fan-in: sub listens to all pub topics.
            if (numPubs == 1) {
                sub.subscribe("bench/topic-0");
            } else {
                for (int t = 0; t < numPubs; t++) sub.subscribe("bench/topic-" + t);
            }
            sub.startReader((t, p) -> {
                metrics.recordReceive(BenchmarkClient.extractTimestamp(p), p.length());
                if (count.incrementAndGet() >= totalExpected) latch.countDown();
            });
            subscribers.add(sub);
        }
        Thread.sleep(200);

        List<BenchmarkClient> publishers = new ArrayList<>();
        for (int p = 0; p < numPubs; p++) {
            BenchmarkClient pub = new BenchmarkClient("hpub-" + p, HOST, HYQP_PORT);
            pub.connect();
            pub.startDrain(); // drain OK responses to prevent TCP buffer fill
            publishers.add(pub);
        }

        // Warmup
        warmupHyqp(publishers, payloadSize);
        metrics.reset();
        count.set(0);

        // Benchmark
        metrics.start();
        List<Thread> threads = new ArrayList<>();
        for (int p = 0; p < numPubs; p++) {
            int pi = p;
            threads.add(Thread.ofVirtual().start(() -> {
                String topic = "bench/topic-" + pi;
                for (int m = 0; m < msgsPerPub; m++) {
                    String payload = BenchmarkClient.buildPayload(m, payloadSize);
                    publishers.get(pi).publish(topic, payload);
                    metrics.recordSend(payload.length());
                }
            }));
        }
        for (Thread t : threads) t.join();
        latch.await(15, TimeUnit.SECONDS);
        Thread.sleep(200);
        metrics.stop();

        for (BenchmarkClient p : publishers) p.disconnect();
        for (BenchmarkClient s : subscribers) s.disconnect();
        broker.stop();
        bt.join(2000);
        Thread.sleep(200);

        return metrics.computeResult(scenario, "HYQP", numPubs, numSubs);
    }

    private void warmupHyqp(List<BenchmarkClient> pubs, int payloadSize) throws Exception {
        for (int w = 0; w < WARMUP; w++) {
            pubs.get(0).publish("bench/topic-0", BenchmarkClient.buildPayload(w, payloadSize));
        }
        Thread.sleep(500);
    }

    // ======================== MQTT ========================

    private BenchmarkResult runMqtt(String scenario, int numPubs, int numSubs,
                                     int msgsPerPub, int payloadSize) throws Exception {
        MetricsCollector metrics = new MetricsCollector();
        long totalExpected = (long) numPubs * msgsPerPub * numSubs;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicLong count = new AtomicLong();

        List<MqttBenchmarkClient> subscribers = new ArrayList<>();
        for (int s = 0; s < numSubs; s++) {
            MqttBenchmarkClient sub = new MqttBenchmarkClient("msub-" + s + "-" + System.nanoTime(), HOST, MQTT_PORT);
            sub.connect();
            if (numPubs == 1) {
                sub.subscribe("bench/topic-0", (t, p) -> {
                    metrics.recordReceive(MqttBenchmarkClient.extractTimestamp(p), p.length());
                    if (count.incrementAndGet() >= totalExpected) latch.countDown();
                });
            } else {
                for (int t2 = 0; t2 < numPubs; t2++) {
                    sub.subscribe("bench/topic-" + t2, (t, p) -> {
                        metrics.recordReceive(MqttBenchmarkClient.extractTimestamp(p), p.length());
                        if (count.incrementAndGet() >= totalExpected) latch.countDown();
                    });
                }
            }
            subscribers.add(sub);
        }
        Thread.sleep(200);

        List<MqttBenchmarkClient> publishers = new ArrayList<>();
        for (int p = 0; p < numPubs; p++) {
            MqttBenchmarkClient pub = new MqttBenchmarkClient("mpub-" + p + "-" + System.nanoTime(), HOST, MQTT_PORT);
            pub.connect();
            publishers.add(pub);
        }

        // Warmup
        for (int w = 0; w < WARMUP; w++) {
            publishers.get(0).publish("bench/topic-0", MqttBenchmarkClient.buildPayload(w, payloadSize));
        }
        Thread.sleep(500);
        metrics.reset();
        count.set(0);

        // Benchmark
        metrics.start();
        List<Thread> threads = new ArrayList<>();
        for (int p = 0; p < numPubs; p++) {
            int pi = p;
            threads.add(Thread.ofVirtual().start(() -> {
                String topic = "bench/topic-" + pi;
                for (int m = 0; m < msgsPerPub; m++) {
                    String payload = MqttBenchmarkClient.buildPayload(m, payloadSize);
                    try {
                        publishers.get(pi).publish(topic, payload);
                    } catch (Exception e) { break; }
                    metrics.recordSend(payload.length());
                }
            }));
        }
        for (Thread t : threads) t.join();
        latch.await(15, TimeUnit.SECONDS);
        Thread.sleep(200);
        metrics.stop();

        for (MqttBenchmarkClient p : publishers) p.disconnect();
        for (MqttBenchmarkClient s : subscribers) s.disconnect();
        Thread.sleep(200);

        return metrics.computeResult(scenario, "MQTT", numPubs, numSubs);
    }

    // ======================== AMQP ========================

    private BenchmarkResult runAmqp(String scenario, int numPubs, int numSubs,
                                     int msgsPerPub, int payloadSize) throws Exception {
        MetricsCollector metrics = new MetricsCollector();
        long totalExpected = (long) numPubs * msgsPerPub * numSubs;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicLong count = new AtomicLong();

        List<AmqpBenchmarkClient> subscribers = new ArrayList<>();
        for (int s = 0; s < numSubs; s++) {
            AmqpBenchmarkClient sub = new AmqpBenchmarkClient(HOST, AMQP_PORT);
            if (numPubs == 1) {
                sub.subscribe("bench.topic.0", (t, p) -> {
                    metrics.recordReceive(AmqpBenchmarkClient.extractTimestamp(p), p.length());
                    if (count.incrementAndGet() >= totalExpected) latch.countDown();
                });
            } else {
                for (int t2 = 0; t2 < numPubs; t2++) {
                    sub.subscribe("bench.topic." + t2, (t, p) -> {
                        metrics.recordReceive(AmqpBenchmarkClient.extractTimestamp(p), p.length());
                        if (count.incrementAndGet() >= totalExpected) latch.countDown();
                    });
                }
            }
            subscribers.add(sub);
        }
        Thread.sleep(200);

        List<AmqpBenchmarkClient> publishers = new ArrayList<>();
        for (int p = 0; p < numPubs; p++) {
            AmqpBenchmarkClient pub = new AmqpBenchmarkClient(HOST, AMQP_PORT);
            publishers.add(pub);
        }

        // Warmup
        for (int w = 0; w < WARMUP; w++) {
            publishers.get(0).publish("bench.topic.0", AmqpBenchmarkClient.buildPayload(w, payloadSize));
        }
        Thread.sleep(500);
        metrics.reset();
        count.set(0);

        // Benchmark
        metrics.start();
        List<Thread> threads = new ArrayList<>();
        for (int p = 0; p < numPubs; p++) {
            int pi = p;
            threads.add(Thread.ofVirtual().start(() -> {
                String topic = "bench.topic." + pi;
                for (int m = 0; m < msgsPerPub; m++) {
                    String payload = AmqpBenchmarkClient.buildPayload(m, payloadSize);
                    try {
                        publishers.get(pi).publish(topic, payload);
                    } catch (Exception e) { break; }
                    metrics.recordSend(payload.length());
                }
            }));
        }
        for (Thread t : threads) t.join();
        latch.await(15, TimeUnit.SECONDS);
        Thread.sleep(200);
        metrics.stop();

        for (AmqpBenchmarkClient p : publishers) p.disconnect();
        for (AmqpBenchmarkClient s : subscribers) s.disconnect();
        Thread.sleep(200);

        return metrics.computeResult(scenario, "AMQP", numPubs, numSubs);
    }

    // ======================== Helpers ========================

    private boolean checkMqtt() {
        try {
            MqttBenchmarkClient c = new MqttBenchmarkClient("check-" + System.nanoTime(), HOST, MQTT_PORT);
            c.connect();
            c.disconnect();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean checkAmqp() {
        try {
            AmqpBenchmarkClient c = new AmqpBenchmarkClient(HOST, AMQP_PORT);
            c.disconnect();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void printShort(BenchmarkResult r) {
        System.out.printf("throughput=%.0f msg/s  p50=%.3fms  p99=%.3fms  loss=%.2f%%%n",
                r.throughputRecv(), r.latencyP50Ms(), r.latencyP99Ms(), r.lossRatePercent());
    }

    private static String formatSize(int bytes) {
        if (bytes >= 1024) return (bytes / 1024) + "KB";
        return bytes + "B";
    }

    private void exportResults() throws IOException {
        Path dir = Path.of("results");
        Files.createDirectories(dir);
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

        // CSV
        String csvFile = "results/comparative_" + ts + ".csv";
        try (PrintWriter csv = new PrintWriter(new FileWriter(csvFile))) {
            csv.println(allResults.get(0).toCsvHeader());
            for (BenchmarkResult r : allResults) csv.println(r.toCsvRow());
        }

        // Summary table
        System.out.println("\n============================================================");
        System.out.println("  COMPARATIVE SUMMARY (averaged over " + runs + " runs)");
        System.out.println("============================================================\n");

        var scenarios = allResults.stream().map(BenchmarkResult::scenario).distinct().toList();
        var protocols = allResults.stream().map(BenchmarkResult::protocol).distinct().toList();

        // Throughput table
        System.out.println("--- Throughput (msg/sec) ---");
        System.out.printf("%-18s", "Scenario");
        for (String p : protocols) System.out.printf("│ %-12s", p);
        System.out.println();
        System.out.println("─".repeat(18) + ("┼" + "─".repeat(13)).repeat(protocols.size()));

        for (String sc : scenarios) {
            System.out.printf("%-18s", sc);
            for (String p : protocols) {
                double avg = allResults.stream()
                        .filter(r -> r.scenario().equals(sc) && r.protocol().equals(p))
                        .mapToDouble(BenchmarkResult::throughputRecv)
                        .average().orElse(0);
                System.out.printf("│ %10.0f  ", avg);
            }
            System.out.println();
        }

        // Latency p99 table
        System.out.println("\n--- Latency p99 (ms) ---");
        System.out.printf("%-18s", "Scenario");
        for (String p : protocols) System.out.printf("│ %-12s", p);
        System.out.println();
        System.out.println("─".repeat(18) + ("┼" + "─".repeat(13)).repeat(protocols.size()));

        for (String sc : scenarios) {
            System.out.printf("%-18s", sc);
            for (String p : protocols) {
                double avg = allResults.stream()
                        .filter(r -> r.scenario().equals(sc) && r.protocol().equals(p))
                        .mapToDouble(BenchmarkResult::latencyP99Ms)
                        .average().orElse(0);
                System.out.printf("│ %10.3f  ", avg);
            }
            System.out.println();
        }

        System.out.println("\nResults saved to: " + csvFile);
    }
}
