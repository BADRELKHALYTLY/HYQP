package com.hyqp.broker.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Standalone subscriber client with optional payload filtering.
 *
 * Usage:
 *   java -cp target/classes com.hyqp.broker.client.Subscriber <name> <topic> [host] [port]
 *   java -cp target/classes com.hyqp.broker.client.Subscriber <name> <topic> <filter_json> [host] [port]
 *
 * Examples:
 *   # No filter — receive all messages
 *   java -cp target/classes com.hyqp.broker.client.Subscriber sub1 sensors/temp
 *
 *   # With filter — only value > 30
 *   java -cp target/classes com.hyqp.broker.client.Subscriber sub1 sensors/temp '{"value":{"$gt":30}}'
 *
 *   # With filter — value between 20 and 80, unit = celsius
 *   java -cp target/classes com.hyqp.broker.client.Subscriber sub1 sensors/temp '{"value":{"$gt":20,"$lt":80},"unit":{"$eq":"celsius"}}'
 */
public class Subscriber {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: Subscriber <name> <topic> [filter_json] [host] [port]");
            System.exit(1);
        }

        String clientName = args[0];
        String topic = args[1];

        // Detect if arg[2] is a filter (starts with {) or a host
        String filterJson = null;
        String host = "localhost";
        int port = 4444;

        int nextArg = 2;
        if (args.length > nextArg && args[nextArg].startsWith("{")) {
            filterJson = args[nextArg];
            nextArg++;
        }
        if (args.length > nextArg) {
            host = args[nextArg];
            nextArg++;
        }
        if (args.length > nextArg) {
            port = Integer.parseInt(args[nextArg]);
        }

        try (Socket socket = new Socket(host, port)) {
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            // Handshake
            out.println("CONNECT " + clientName);
            System.out.println(in.readLine());

            // Subscribe (with or without filter)
            if (filterJson != null) {
                out.println("SUBSCRIBE " + topic + " FILTER " + filterJson);
            } else {
                out.println("SUBSCRIBE " + topic);
            }
            System.out.println(in.readLine());

            System.out.println("[" + clientName + "] Listening on topic: " + topic);
            if (filterJson != null) {
                System.out.println("[" + clientName + "] Filter: " + filterJson);
            }
            System.out.println("Press Ctrl+C to stop.\n");

            // Listen for messages
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("MESSAGE ")) {
                    String rest = line.substring("MESSAGE ".length());
                    int spaceIdx = rest.indexOf(' ');
                    String msgTopic = rest.substring(0, spaceIdx);
                    String payload = rest.substring(spaceIdx + 1);
                    System.out.println("[" + msgTopic + "] " + payload);
                } else {
                    System.out.println(line);
                }
            }
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
        }
    }
}
