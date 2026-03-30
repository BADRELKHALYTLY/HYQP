package com.hyqp.broker.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * Simple interactive CLI client for manual testing.
 *
 * Usage: java -cp ... com.hyqp.broker.client.TestClient [host] [port]
 * Default: localhost 4444
 *
 * Type commands directly:
 *   CONNECT myClient
 *   SUBSCRIBE sensors/temperature
 *   PUBLISH sensors/temperature {"value": 22.5}
 *   DISCONNECT
 */
public class TestClient {

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 4444;

        try (Socket socket = new Socket(host, port)) {
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            // Reader thread: print server messages to stdout
            Thread readerThread = Thread.ofVirtual().name("reader").start(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        System.out.println("<< " + line);
                    }
                } catch (IOException e) {
                    if (!socket.isClosed()) {
                        System.err.println("Connection lost: " + e.getMessage());
                    }
                }
            });

            // Main thread: read from stdin and send to server
            Scanner scanner = new Scanner(System.in);
            System.out.println("Connected to " + host + ":" + port);
            System.out.println("Type commands (CONNECT, SUBSCRIBE, PUBLISH, UNSUBSCRIBE, DISCONNECT):");

            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().strip();
                if (line.isEmpty()) continue;
                out.println(line);
                if (line.equalsIgnoreCase("DISCONNECT")) break;
            }

            readerThread.join(1000);
        } catch (IOException e) {
            System.err.println("Connection error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
