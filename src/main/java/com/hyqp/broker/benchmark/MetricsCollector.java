package com.hyqp.broker.benchmark;

import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects and computes benchmark metrics: latency percentiles, throughput, loss rate.
 */
public class MetricsCollector {

    private final CopyOnWriteArrayList<Long> latenciesNanos = new CopyOnWriteArrayList<>();
    private final AtomicLong messagesSent = new AtomicLong();
    private final AtomicLong messagesReceived = new AtomicLong();
    private final AtomicLong bytesPublished = new AtomicLong();
    private final AtomicLong bytesReceived = new AtomicLong();
    private volatile long startTimeNanos;
    private volatile long endTimeNanos;

    public void start() {
        startTimeNanos = System.nanoTime();
    }

    public void stop() {
        endTimeNanos = System.nanoTime();
    }

    public void recordSend(int payloadBytes) {
        messagesSent.incrementAndGet();
        bytesPublished.addAndGet(payloadBytes);
    }

    public void recordReceive(long sendTimestampNanos, int payloadBytes) {
        messagesReceived.incrementAndGet();
        bytesReceived.addAndGet(payloadBytes);
        long latency = System.nanoTime() - sendTimestampNanos;
        latenciesNanos.add(latency);
    }

    public BenchmarkResult computeResult(String scenario, String protocol, int publishers, int subscribers) {
        long[] sorted = latenciesNanos.stream().mapToLong(Long::longValue).sorted().toArray();
        long durationNanos = endTimeNanos - startTimeNanos;
        double durationSec = durationNanos / 1_000_000_000.0;

        double p50 = percentile(sorted, 50);
        double p95 = percentile(sorted, 95);
        double p99 = percentile(sorted, 99);
        double mean = sorted.length > 0 ? Arrays.stream(sorted).average().orElse(0) : 0;
        double stdDev = computeStdDev(sorted, mean);

        double throughputSend = messagesSent.get() / durationSec;
        double throughputRecv = messagesReceived.get() / durationSec;
        double lossRate = messagesSent.get() > 0
                ? (1.0 - (double) messagesReceived.get() / (messagesSent.get() * subscribers)) * 100.0
                : 0;

        return new BenchmarkResult(
                scenario, protocol, publishers, subscribers,
                messagesSent.get(), messagesReceived.get(),
                durationSec, throughputSend, throughputRecv,
                nanosToMs(mean), nanosToMs(stdDev),
                nanosToMs(p50), nanosToMs(p95), nanosToMs(p99),
                nanosToMs(sorted.length > 0 ? sorted[0] : 0),
                nanosToMs(sorted.length > 0 ? sorted[sorted.length - 1] : 0),
                lossRate, bytesPublished.get(), bytesReceived.get()
        );
    }

    public void reset() {
        latenciesNanos.clear();
        messagesSent.set(0);
        messagesReceived.set(0);
        bytesPublished.set(0);
        bytesReceived.set(0);
    }

    private double percentile(long[] sorted, double p) {
        if (sorted.length == 0) return 0;
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    private double computeStdDev(long[] values, double mean) {
        if (values.length < 2) return 0;
        double sumSq = 0;
        for (long v : values) {
            double diff = v - mean;
            sumSq += diff * diff;
        }
        return Math.sqrt(sumSq / (values.length - 1));
    }

    private double nanosToMs(double nanos) {
        return nanos / 1_000_000.0;
    }
}
