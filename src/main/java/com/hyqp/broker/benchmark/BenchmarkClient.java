package com.hyqp.broker.benchmark;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

/**
 * Lightweight benchmark client for HYQP.
 * Supports both publisher and subscriber roles.
 */
public class BenchmarkClient {

    private final String name;
    private final Socket socket;
    private final BufferedReader in;
    private final PrintWriter out;
    private volatile boolean running = true;
    private Thread readerThread;

    public BenchmarkClient(String name, String host, int port) throws IOException {
        this.name = name;
        this.socket = new Socket(host, port);
        this.socket.setTcpNoDelay(true);
        this.in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
    }

    public void connect() throws IOException {
        out.println("CONNECT " + name);
        in.readLine(); // OK Connected
    }

    public void subscribe(String topic) throws IOException {
        out.println("SUBSCRIBE " + topic);
        in.readLine(); // OK Subscribed
    }

    /**
     * Start async reader that invokes callback on each MESSAGE.
     * Callback receives (topic, payload).
     */
    public void startReader(BiConsumer<String, String> onMessage) {
        readerThread = Thread.ofVirtual().name("bench-reader-" + name).start(() -> {
            try {
                String line;
                while (running && (line = in.readLine()) != null) {
                    if (line.startsWith("MESSAGE ")) {
                        String rest = line.substring(8);
                        int sp = rest.indexOf(' ');
                        if (sp > 0) {
                            onMessage.accept(rest.substring(0, sp), rest.substring(sp + 1));
                        }
                    }
                }
            } catch (IOException e) {
                // connection closed
            }
        });
    }

    /**
     * Start a reader thread that silently drains all server responses (OK, ERROR).
     * Used by publishers to prevent TCP buffer from filling up.
     */
    public void startDrain() {
        readerThread = Thread.ofVirtual().name("bench-drain-" + name).start(() -> {
            try {
                while (running && in.readLine() != null) {
                    // discard all responses
                }
            } catch (IOException e) {
                // connection closed
            }
        });
    }

    /**
     * Publish with embedded nanoTime timestamp for latency measurement.
     */
    public void publish(String topic, String payload) {
        out.println("PUBLISH " + topic + " " + payload);
    }

    /**
     * Build a payload with embedded timestamp and padding to target size.
     */
    public static String buildPayload(int seq, int targetBytes) {
        long ts = System.nanoTime();
        String base = "{\"_ts\":" + ts + ",\"_seq\":" + seq;
        int remaining = targetBytes - base.length() - 2; // for closing }
        if (remaining > 10) {
            StringBuilder sb = new StringBuilder(base);
            sb.append(",\"_pad\":\"");
            sb.append("X".repeat(Math.max(0, remaining - 10)));
            sb.append("\"}");
            return sb.toString();
        }
        return base + "}";
    }

    /**
     * Extract the _ts field from a benchmark payload.
     */
    public static long extractTimestamp(String payload) {
        int idx = payload.indexOf("\"_ts\":");
        if (idx == -1) return 0;
        int start = idx + 6;
        int end = payload.indexOf(',', start);
        if (end == -1) end = payload.indexOf('}', start);
        return Long.parseLong(payload.substring(start, end));
    }

    public void disconnect() {
        running = false;
        out.println("DISCONNECT");
        try {
            if (readerThread != null) readerThread.join(2000);
            socket.close();
        } catch (Exception ignored) {}
    }
}
