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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Automated benchmark runner for HYQP.
 *
 * Scenarios:
 *   1. one-to-one:   1 pub, 1 sub, 1 topic
 *   2. fan-out:      1 pub, N subs, 1 topic
 *   3. fan-in:       N pubs, 1 sub, 1 topic
 *   4. multi-topic:  N pubs, N subs, N topics
 *   5. payload-size: 1 pub, 1 sub, varying payload sizes
 *   6. burst:        1 pub, 1 sub, 10000 messages burst
 *
 * Usage: java -cp target/classes com.hyqp.broker.benchmark.BenchmarkRunner [runs]
 */
public class BenchmarkRunner {

    private static final String HOST = "localhost";
    private static final int PORT = 14444; // dedicated benchmark port
    private static final int WARMUP_MESSAGES = 500;
    private static final int DEFAULT_RUNS = 5;

    private final int runs;
    private final List<BenchmarkResult> allResults = new ArrayList<>();

    public BenchmarkRunner(int runs) {
        this.runs = runs;
    }

    public static void main(String[] args) throws Exception {
        int runs = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_RUNS;

        System.out.println("============================================================");
        System.out.println("  HYQP Benchmark Suite");
        System.out.println("  " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        System.out.println("  Runs per scenario: " + runs);
        System.out.println("============================================================\n");

        BenchmarkRunner runner = new BenchmarkRunner(runs);
        runner.runAll();
        runner.exportResults();
    }

    public void runAll() throws Exception {
        runScenario("one-to-one", 1, 1, 1, 10000, 128);
        runScenario("fan-out-10", 1, 10, 1, 5000, 128);
        runScenario("fan-out-50", 1, 50, 1, 5000, 128);
        runScenario("fan-out-100", 1, 100, 1, 2000, 128);
        runScenario("fan-in-10", 10, 1, 10, 5000, 128);
        runScenario("fan-in-50", 50, 1, 50, 2000, 128);
        runScenario("multi-topic-10", 10, 10, 10, 3000, 128);
        runScenario("payload-64B", 1, 1, 1, 10000, 64);
        runScenario("payload-256B", 1, 1, 1, 10000, 256);
        runScenario("payload-1KB", 1, 1, 1, 10000, 1024);
        runScenario("payload-10KB", 1, 1, 1, 5000, 10240);
        runScenario("burst-10k", 1, 1, 1, 10000, 128);
        runScenario("burst-50k", 1, 1, 1, 50000, 128);
    }

    private void runScenario(String name, int numPubs, int numSubs, int numTopics,
                              int messagesPerPub, int payloadSize) throws Exception {
        System.out.println("--- Scenario: " + name + " ---");
        System.out.printf("    %d pub(s), %d sub(s), %d topic(s), %d msg/pub, %dB payload%n",
                numPubs, numSubs, numTopics, messagesPerPub, payloadSize);

        for (int run = 1; run <= runs; run++) {
            System.out.printf("    Run %d/%d ... ", run, runs);
            BenchmarkResult result = executeSingle(name, numPubs, numSubs, numTopics,
                    messagesPerPub, payloadSize);
            allResults.add(result);
            System.out.printf("throughput=%.0f msg/s  p50=%.3fms  p99=%.3fms  loss=%.2f%%%n",
                    result.throughputRecv(), result.latencyP50Ms(),
                    result.latencyP99Ms(), result.lossRatePercent());
        }
        System.out.println();
    }

    private BenchmarkResult executeSingle(String scenario, int numPubs, int numSubs,
                                           int numTopics, int messagesPerPub,
                                           int payloadSize) throws Exception {
        // Start embedded broker
        TopicRegistry registry = new TopicRegistry();
        MessageDispatcher dispatcher = new MessageDispatcher(registry);
        Broker broker = new Broker(PORT, registry, dispatcher);
        Thread brokerThread = Thread.ofVirtual().name("bench-broker").start(() -> {
            try { broker.start(); } catch (IOException ignored) {}
        });
        Thread.sleep(300);

        MetricsCollector metrics = new MetricsCollector();
        long totalExpected = (long) numPubs * messagesPerPub * numSubs;

        CountDownLatch allReceived = new CountDownLatch(1);
        var receivedCount = new java.util.concurrent.atomic.AtomicLong(0);

        // Create subscribers
        List<BenchmarkClient> subscribers = new ArrayList<>();
        for (int s = 0; s < numSubs; s++) {
            BenchmarkClient sub = new BenchmarkClient("bench-sub-" + s, HOST, PORT);
            sub.connect();
            for (int t = 0; t < numTopics; t++) {
                sub.subscribe("bench/topic-" + (t % numTopics));
            }
            sub.startReader((topic, payload) -> {
                long ts = BenchmarkClient.extractTimestamp(payload);
                metrics.recordReceive(ts, payload.length());
                if (receivedCount.incrementAndGet() >= totalExpected) {
                    allReceived.countDown();
                }
            });
            subscribers.add(sub);
        }
        Thread.sleep(200);

        // Create publishers
        List<BenchmarkClient> publishers = new ArrayList<>();
        for (int p = 0; p < numPubs; p++) {
            BenchmarkClient pub = new BenchmarkClient("bench-pub-" + p, HOST, PORT);
            pub.connect();
            pub.startDrain();
            publishers.add(pub);
        }

        // Warmup
        for (int w = 0; w < WARMUP_MESSAGES; w++) {
            String payload = BenchmarkClient.buildPayload(w, payloadSize);
            publishers.get(0).publish("bench/topic-0", payload);
        }
        Thread.sleep(500);
        metrics.reset();
        receivedCount.set(0);

        // Benchmark run
        metrics.start();

        List<Thread> pubThreads = new ArrayList<>();
        for (int p = 0; p < numPubs; p++) {
            int pubIdx = p;
            Thread t = Thread.ofVirtual().name("bench-pub-thread-" + p).start(() -> {
                BenchmarkClient pub = publishers.get(pubIdx);
                String topic = "bench/topic-" + (pubIdx % numTopics);
                for (int m = 0; m < messagesPerPub; m++) {
                    String payload = BenchmarkClient.buildPayload(m, payloadSize);
                    pub.publish(topic, payload);
                    metrics.recordSend(payload.length());
                }
            });
            pubThreads.add(t);
        }

        // Wait for all publishers to finish
        for (Thread t : pubThreads) t.join();

        // Wait for all messages to be received (with timeout)
        allReceived.await(30, TimeUnit.SECONDS);
        Thread.sleep(200);

        metrics.stop();

        // Cleanup
        for (BenchmarkClient pub : publishers) pub.disconnect();
        for (BenchmarkClient sub : subscribers) sub.disconnect();
        broker.stop();
        brokerThread.join(2000);
        Thread.sleep(200);

        return metrics.computeResult(scenario, "HYQP", numPubs, numSubs);
    }

    private void exportResults() throws IOException {
        Path dir = Path.of("results");
        Files.createDirectories(dir);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String csvFile = "results/benchmark_" + timestamp + ".csv";
        String reportFile = "results/benchmark_" + timestamp + ".txt";

        // CSV export
        try (PrintWriter csv = new PrintWriter(new FileWriter(csvFile))) {
            csv.println(allResults.get(0).toCsvHeader());
            for (BenchmarkResult r : allResults) {
                csv.println(r.toCsvRow());
            }
        }

        // Console report
        try (PrintWriter report = new PrintWriter(new FileWriter(reportFile))) {
            String header = """
                    ============================================================
                      HYQP Benchmark Results
                      %s
                      Runs per scenario: %d
                    ============================================================
                    """.formatted(
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    runs
            );
            System.out.println(header);
            report.println(header);

            // Aggregate by scenario (average across runs)
            var scenarios = allResults.stream()
                    .map(BenchmarkResult::scenario)
                    .distinct()
                    .toList();

            System.out.println("┌──────────────────┬──────────┬──────────┬──────────┬──────────┬────────┐");
            System.out.println("│ Scenario         │ Thru/s   │ p50 (ms) │ p95 (ms) │ p99 (ms) │ Loss % │");
            System.out.println("├──────────────────┼──────────┼──────────┼──────────┼──────────┼────────┤");

            report.println("| Scenario | Throughput | p50 (ms) | p95 (ms) | p99 (ms) | Loss % |");
            report.println("|----------|-----------|----------|----------|----------|--------|");

            for (String sc : scenarios) {
                var scResults = allResults.stream()
                        .filter(r -> r.scenario().equals(sc))
                        .toList();

                double avgThru = scResults.stream().mapToDouble(BenchmarkResult::throughputRecv).average().orElse(0);
                double avgP50 = scResults.stream().mapToDouble(BenchmarkResult::latencyP50Ms).average().orElse(0);
                double avgP95 = scResults.stream().mapToDouble(BenchmarkResult::latencyP95Ms).average().orElse(0);
                double avgP99 = scResults.stream().mapToDouble(BenchmarkResult::latencyP99Ms).average().orElse(0);
                double avgLoss = scResults.stream().mapToDouble(BenchmarkResult::lossRatePercent).average().orElse(0);

                String row = String.format("│ %-16s │ %8.0f │ %8.3f │ %8.3f │ %8.3f │ %5.2f%% │",
                        sc, avgThru, avgP50, avgP95, avgP99, avgLoss);
                System.out.println(row);

                report.printf("| %s | %.0f | %.3f | %.3f | %.3f | %.2f%% |%n",
                        sc, avgThru, avgP50, avgP95, avgP99, avgLoss);
            }

            System.out.println("└──────────────────┴──────────┴──────────┴──────────┴──────────┴────────┘");
        }

        System.out.println("\nResults saved to:");
        System.out.println("  CSV:    " + csvFile);
        System.out.println("  Report: " + reportFile);
    }
}
