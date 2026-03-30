package com.hyqp.broker.benchmark;

/**
 * Immutable result of a single benchmark run.
 */
public record BenchmarkResult(
        String scenario,
        String protocol,
        int publishers,
        int subscribers,
        long messagesSent,
        long messagesReceived,
        double durationSec,
        double throughputSend,
        double throughputRecv,
        double latencyMeanMs,
        double latencyStdDevMs,
        double latencyP50Ms,
        double latencyP95Ms,
        double latencyP99Ms,
        double latencyMinMs,
        double latencyMaxMs,
        double lossRatePercent,
        long bytesPublished,
        long bytesReceived
) {

    public String toConsole() {
        return String.format("""
                ┌─────────────────────────────────────────────────┐
                │  Scenario:    %-35s│
                │  Protocol:    %-35s│
                │  Publishers:  %-5d  Subscribers: %-15d│
                ├─────────────────────────────────────────────────┤
                │  Messages sent:      %-27d│
                │  Messages received:  %-27d│
                │  Duration:           %-24.2fs│
                │  Throughput (pub):   %-22.0f msg/s│
                │  Throughput (recv):  %-22.0f msg/s│
                ├─────────────────────────────────────────────────┤
                │  Latency mean:       %-24.3f ms│
                │  Latency std dev:    %-24.3f ms│
                │  Latency p50:        %-24.3f ms│
                │  Latency p95:        %-24.3f ms│
                │  Latency p99:        %-24.3f ms│
                │  Latency min:        %-24.3f ms│
                │  Latency max:        %-24.3f ms│
                ├─────────────────────────────────────────────────┤
                │  Loss rate:          %-23.2f %%│
                │  Bytes published:    %-27d│
                │  Bytes received:     %-27d│
                └─────────────────────────────────────────────────┘
                """,
                scenario, protocol, publishers, subscribers,
                messagesSent, messagesReceived,
                durationSec, throughputSend, throughputRecv,
                latencyMeanMs, latencyStdDevMs,
                latencyP50Ms, latencyP95Ms, latencyP99Ms,
                latencyMinMs, latencyMaxMs,
                lossRatePercent, bytesPublished, bytesReceived
        );
    }

    public String toCsvHeader() {
        return "scenario,protocol,publishers,subscribers,messages_sent,messages_received,"
                + "duration_sec,throughput_send,throughput_recv,"
                + "latency_mean_ms,latency_stddev_ms,latency_p50_ms,latency_p95_ms,latency_p99_ms,"
                + "latency_min_ms,latency_max_ms,loss_rate_percent,bytes_published,bytes_received";
    }

    public String toCsvRow() {
        return String.format("%s,%s,%d,%d,%d,%d,%.4f,%.2f,%.2f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.2f,%d,%d",
                scenario, protocol, publishers, subscribers,
                messagesSent, messagesReceived,
                durationSec, throughputSend, throughputRecv,
                latencyMeanMs, latencyStdDevMs,
                latencyP50Ms, latencyP95Ms, latencyP99Ms,
                latencyMinMs, latencyMaxMs,
                lossRatePercent, bytesPublished, bytesReceived
        );
    }
}
