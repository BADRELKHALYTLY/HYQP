package com.hyqp.broker.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * Interactive publisher client with CREATE and PUBLISH commands.
 *
 * Usage: java -cp target/classes com.hyqp.broker.client.Publisher <clientName> [host] [port]
 *
 * Commands:
 *   CREATE <topic>            — create a new topic on the broker
 *   PUBLISH <topic> <payload> — publish a message to a topic
 *   DISCONNECT                — quit
 */
public class Publisher {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: Publisher <clientName> [host] [port]");
            System.exit(1);
        }

        String clientName = args[0];
        String host = args.length > 1 ? args[1] : "localhost";
        int port = args.length > 2 ? Integer.parseInt(args[2]) : 4444;

        try (Socket socket = new Socket(host, port)) {
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            // Reader thread: print server responses
            Thread.ofVirtual().name("pub-reader").start(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        System.out.println("  << " + line);
                    }
                } catch (IOException e) {
                    if (!socket.isClosed()) {
                        System.err.println("Connection lost: " + e.getMessage());
                    }
                }
            });

            // Handshake
            out.println("CONNECT " + clientName);
            Thread.sleep(300);

            System.out.println("=== HYQP Publisher [" + clientName + "] ===");
            System.out.println("Commands:");
            System.out.println("  CREATE <topic>            — create a new topic");
            System.out.println("  PUBLISH <topic> <payload> — publish a message");
            System.out.println("  DISCONNECT                — quit");
            System.out.println();

            Scanner scanner = new Scanner(System.in);
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().strip();
                if (line.isEmpty()) continue;

                String upper = line.toUpperCase();
                if (upper.startsWith("CREATE ") || upper.startsWith("PUBLISH ") || upper.equals("DISCONNECT")) {
                    out.println(line);
                    if (upper.equals("DISCONNECT")) break;
                } else {
                    System.out.println("  ?? Unknown command. Use CREATE, PUBLISH, or DISCONNECT.");
                }
            }

            Thread.sleep(300);
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
