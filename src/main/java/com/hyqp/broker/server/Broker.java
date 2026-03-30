package com.hyqp.broker.server;

import com.hyqp.broker.dispatch.MessageDispatcher;
import com.hyqp.broker.persistence.RetainedStore;
import com.hyqp.broker.persistence.SessionStore;
import com.hyqp.broker.topic.TopicRegistry;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Broker {

    private static final Logger LOG = Logger.getLogger(Broker.class.getName());

    private final int port;
    private final TopicRegistry registry;
    private final MessageDispatcher dispatcher;
    private final RetainedStore retainedStore;
    private final SessionStore sessionStore;
    private final ConcurrentHashMap<String, ClientSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong clientCounter = new AtomicLong();

    private volatile ServerSocket serverSocket;
    private volatile boolean running;

    public Broker(int port, TopicRegistry registry, MessageDispatcher dispatcher) {
        this(port, registry, dispatcher, null, null);
    }

    public Broker(int port, TopicRegistry registry, MessageDispatcher dispatcher,
                  RetainedStore retainedStore, SessionStore sessionStore) {
        this.port = port;
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.retainedStore = retainedStore;
        this.sessionStore = sessionStore;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        LOG.info(() -> "HYQP Broker listening on port " + port);

        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                long num = clientCounter.incrementAndGet();
                Thread.ofVirtual()
                      .name("client-" + num)
                      .start(() -> handleClient(clientSocket));
            } catch (IOException e) {
                if (running) {
                    LOG.log(Level.WARNING, "Accept failed", e);
                }
            }
        }
    }

    private void handleClient(Socket clientSocket) {
        try {
            RetainedStore rs = retainedStore != null ? retainedStore : new RetainedStore(java.nio.file.Path.of("data/retained"));
            SessionStore ss = sessionStore != null ? sessionStore : new SessionStore(java.nio.file.Path.of("data/sessions"));
            ClientSession session = new ClientSession(clientSocket, registry, dispatcher, this, rs, ss);
            sessions.put(session.getSessionId(), session);
            session.run();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to create session", e);
            try { clientSocket.close(); } catch (IOException ignored) {}
        }
    }

    public void stop() {
        running = false;
        LOG.info("Broker shutting down...");
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException e) {
                LOG.log(Level.WARNING, "Error closing server socket", e);
            }
        }
        for (ClientSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
        LOG.info("Broker stopped.");
    }

    void removeSession(String sessionId) {
        sessions.remove(sessionId);
    }

    public int getActiveSessionCount() {
        return sessions.size();
    }
}
