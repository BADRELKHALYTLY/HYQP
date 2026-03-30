package com.hyqp.broker.server;

import com.hyqp.broker.dispatch.MessageDispatcher;
import com.hyqp.broker.persistence.RetainedStore;
import com.hyqp.broker.persistence.SessionStore;
import com.hyqp.broker.protocol.Command;
import com.hyqp.broker.protocol.ProtocolException;
import com.hyqp.broker.protocol.ProtocolParser;
import com.hyqp.broker.qos.PendingMessage;
import com.hyqp.broker.qos.QosLevel;
import com.hyqp.broker.qos.QosManager;
import com.hyqp.broker.schema.JsonParseException;
import com.hyqp.broker.schema.JsonParser;
import com.hyqp.broker.schema.JsonValue;
import com.hyqp.broker.schema.PayloadFilter;
import com.hyqp.broker.schema.SchemaValidator;
import com.hyqp.broker.topic.TopicMatcher;
import com.hyqp.broker.topic.TopicRegistry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ClientSession {

    private static final Logger LOG = Logger.getLogger(ClientSession.class.getName());

    private final String sessionId = UUID.randomUUID().toString();
    private final Socket socket;
    private final BufferedReader in;
    private final PrintWriter out;
    private final TopicRegistry registry;
    private final MessageDispatcher dispatcher;
    private final Broker broker;
    private final RetainedStore retainedStore;
    private final SessionStore sessionStore;
    private final Set<String> subscriptions = new CopyOnWriteArraySet<>();
    private final Map<String, JsonValue> subscriptionFilters = new ConcurrentHashMap<>();
    private final AtomicBoolean active = new AtomicBoolean(true);

    private volatile String clientName;
    private volatile boolean persistent;
    private QosManager qosManager;

    public ClientSession(Socket socket, TopicRegistry registry,
                         MessageDispatcher dispatcher, Broker broker,
                         RetainedStore retainedStore, SessionStore sessionStore) throws IOException {
        this.socket = socket;
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.broker = broker;
        this.retainedStore = retainedStore;
        this.sessionStore = sessionStore;
        this.in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
        this.qosManager = new QosManager(this::retrySend);
    }

    public void run() {
        // Start QoS retry timer
        Thread retryThread = Thread.ofVirtual().name("qos-retry-" + sessionId).start(() -> {
            while (active.get()) {
                try {
                    Thread.sleep(5000);
                    qosManager.retryExpired();
                } catch (InterruptedException e) {
                    break;
                }
            }
        });

        try {
            String line;
            while (active.get() && (line = in.readLine()) != null) {
                try {
                    Command cmd = ProtocolParser.parse(line);
                    handle(cmd);
                } catch (ProtocolException e) {
                    send("ERROR " + e.getMessage());
                }
            }
        } catch (IOException e) {
            if (active.get()) {
                LOG.log(Level.FINE, "Connection lost for session {0}: {1}",
                        new Object[]{sessionId, e.getMessage()});
            }
        } finally {
            retryThread.interrupt();
            close();
        }
    }

    private void handle(Command cmd) {
        switch (cmd.type()) {
            case CONNECT -> {
                this.clientName = cmd.clientName();
                this.persistent = cmd.persistent();
                LOG.info(() -> "Client connected: " + clientName + " [" + sessionId + "]"
                        + (persistent ? " (persistent)" : ""));

                // Session recovery
                if (persistent && sessionStore.hasSession(clientName)) {
                    restoreSession();
                    send("OK Connected as " + clientName + " (session restored)");
                } else {
                    send("OK Connected as " + clientName);
                }
            }
            case CREATE -> {
                String topic = cmd.topic();
                String schemaStr = cmd.payload();
                JsonValue schema = null;
                if (schemaStr != null) {
                    try {
                        schema = JsonParser.parse(schemaStr);
                        if (!schema.isObject()) {
                            send("ERROR Schema must be a JSON object");
                            break;
                        }
                    } catch (JsonParseException e) {
                        send("ERROR Invalid schema JSON: " + e.getMessage());
                        break;
                    }
                }
                if (registry.createTopic(topic, schema)) {
                    String msg = schema != null
                        ? "OK Topic created with schema: " + topic
                        : "OK Topic created: " + topic;
                    LOG.info(() -> clientName + " created topic " + topic);
                    send(msg);
                } else {
                    send("ERROR Topic already exists: " + topic);
                }
            }
            case SUBSCRIBE -> {
                String topic = cmd.topic();
                String filterStr = cmd.payload();
                JsonValue filter = null;
                if (filterStr != null) {
                    try {
                        filter = JsonParser.parse(filterStr);
                        if (!filter.isObject()) {
                            send("ERROR Filter must be a JSON object");
                            break;
                        }
                    } catch (JsonParseException e) {
                        send("ERROR Invalid filter JSON: " + e.getMessage());
                        break;
                    }
                }
                boolean alreadySubscribed = subscriptions.contains(topic);
                subscriptions.add(topic);
                if (filter != null) {
                    subscriptionFilters.put(topic, filter);
                } else {
                    subscriptionFilters.remove(topic);
                }
                if (!alreadySubscribed) {
                    registry.subscribe(topic, this);
                }

                String msg;
                if (alreadySubscribed && filter != null) {
                    msg = "OK Filter updated on " + topic;
                } else if (alreadySubscribed) {
                    msg = "OK Filter removed on " + topic;
                } else if (filter != null) {
                    msg = "OK Subscribed to " + topic + " with filter";
                } else {
                    msg = "OK Subscribed to " + topic;
                }
                send(msg);

                // Send retained messages
                if (!alreadySubscribed) {
                    sendRetainedMessages(topic);
                }

                // Persist session if persistent
                if (persistent) {
                    persistSession();
                }
            }
            case UNSUBSCRIBE -> {
                String topic = cmd.topic();
                subscriptions.remove(topic);
                subscriptionFilters.remove(topic);
                registry.unsubscribe(topic, this);
                send("OK Unsubscribed from " + topic);
                if (persistent) {
                    persistSession();
                }
            }
            case PUBLISH -> handlePublish(cmd);
            case DISCONNECT -> {
                send("OK Goodbye");
                if (!persistent) {
                    // Clean session: remove stored state
                    if (clientName != null) {
                        sessionStore.removeSession(clientName);
                    }
                }
                close();
            }
            case PUBACK -> {
                qosManager.handlePuback(cmd.messageId());
            }
            case PUBREC -> {
                PendingMessage pm = qosManager.handlePubrec(cmd.messageId());
                if (pm != null) {
                    send("PUBREL " + cmd.messageId());
                }
            }
            case PUBREL -> {
                // Subscriber side: we received PUBREL, send PUBCOMP
                send("PUBCOMP " + cmd.messageId());
            }
            case PUBCOMP -> {
                qosManager.handlePubcomp(cmd.messageId());
            }
        }
    }

    private void handlePublish(Command cmd) {
        String topic = cmd.topic();
        String payload = cmd.payload();
        QosLevel qos = cmd.qos() != null ? cmd.qos() : QosLevel.QOS0;

        // Schema validation
        JsonValue schema = registry.getSchema(topic);
        if (schema != null) {
            try {
                JsonValue parsed = JsonParser.parse(payload);
                List<String> errors = SchemaValidator.validate(parsed, schema);
                if (!errors.isEmpty()) {
                    send("ERROR Schema validation failed: " + String.join("; ", errors));
                    return;
                }
            } catch (JsonParseException e) {
                send("ERROR Invalid JSON payload: " + e.getMessage());
                return;
            }
        }

        // Retained message handling
        if (cmd.retain()) {
            retainedStore.setRetained(topic, payload);
        }

        LOG.fine(() -> clientName + " published to " + topic + " (QoS " + qos.value() + ")");
        dispatcher.dispatch(topic, payload, this, qos);
        send("OK Published to " + topic);
    }

    /**
     * Send retained messages matching the subscription topic.
     */
    private void sendRetainedMessages(String subscribedTopic) {
        for (var entry : retainedStore.getAllRetained().entrySet()) {
            String retainedTopic = entry.getKey();
            String retainedPayload = entry.getValue();
            if (retainedTopic.equals(subscribedTopic)
                    || TopicMatcher.matches(subscribedTopic, retainedTopic)) {
                if (acceptsPayload(retainedTopic, retainedPayload)) {
                    send("MESSAGE " + retainedTopic + " " + retainedPayload);
                }
            }
        }
    }

    /**
     * Restore session state from persistent storage.
     */
    private void restoreSession() {
        SessionStore.StoredSession stored = sessionStore.getSession(clientName);
        if (stored == null) return;

        LOG.info(() -> "Restoring session for " + clientName + " (" + stored.subscriptions().size() + " subs)");
        for (var entry : stored.subscriptions().entrySet()) {
            String topic = entry.getKey();
            String filterStr = entry.getValue();
            subscriptions.add(topic);
            if (filterStr != null) {
                try {
                    subscriptionFilters.put(topic, JsonParser.parse(filterStr));
                } catch (JsonParseException ignored) {}
            }
            registry.subscribe(topic, this);
        }
    }

    private void persistSession() {
        if (clientName == null) return;
        Map<String, String> subs = new HashMap<>();
        for (String topic : subscriptions) {
            JsonValue filter = subscriptionFilters.get(topic);
            subs.put(topic, filter != null ? filter.toString() : null);
        }
        sessionStore.saveSession(clientName, subs);
    }

    /**
     * Send a message to this client with QoS support.
     */
    public void sendWithQos(String topic, String payload, QosLevel qos) {
        if (!active.get()) return;

        if (qos == QosLevel.QOS0) {
            send("MESSAGE " + topic + " " + payload);
        } else {
            long msgId = qosManager.nextMessageId();
            PendingMessage pm = new PendingMessage(msgId, topic, payload, qos);
            qosManager.addPending(pm);
            send("MESSAGE " + msgId + " " + qos.name() + " " + topic + " " + payload);
        }
    }

    private void retrySend(PendingMessage pm) {
        if (!active.get()) return;
        if (pm.getState() == PendingMessage.State.AWAITING_PUBACK
                || pm.getState() == PendingMessage.State.AWAITING_PUBREC) {
            send("MESSAGE " + pm.getMessageId() + " " + pm.getQos().name()
                    + " " + pm.getTopic() + " " + pm.getPayload());
        } else if (pm.getState() == PendingMessage.State.AWAITING_PUBCOMP) {
            send("PUBREL " + pm.getMessageId());
        }
    }

    public synchronized void send(String message) {
        if (active.get()) {
            out.println(message);
        }
    }

    public void close() {
        if (active.compareAndSet(true, false)) {
            LOG.info(() -> "Session closing: " + (clientName != null ? clientName : sessionId));
            if (persistent) {
                persistSession();
            }
            registry.unsubscribeAll(this);
            broker.removeSession(sessionId);
            qosManager.clearAll();
            try {
                socket.close();
            } catch (IOException e) {
                LOG.log(Level.FINE, "Error closing socket for " + sessionId, e);
            }
        }
    }

    public boolean acceptsPayload(String topic, String payload) {
        JsonValue filter = subscriptionFilters.get(topic);
        if (filter == null) {
            for (var entry : subscriptionFilters.entrySet()) {
                String subTopic = entry.getKey();
                if (TopicMatcher.isWildcard(subTopic)
                        && TopicMatcher.matches(subTopic, topic)) {
                    filter = entry.getValue();
                    break;
                }
            }
        }
        if (filter == null) return true;
        try {
            JsonValue parsedPayload = JsonParser.parse(payload);
            return PayloadFilter.matches(parsedPayload, filter);
        } catch (JsonParseException e) {
            return true;
        }
    }

    public String getSessionId() { return sessionId; }
    public String getClientName() { return clientName; }
    public Set<String> getSubscriptions() { return Collections.unmodifiableSet(subscriptions); }
    public boolean isActive() { return active.get(); }
    public boolean isPersistent() { return persistent; }

    @Override
    public String toString() {
        return "ClientSession{" + (clientName != null ? clientName : sessionId) + "}";
    }
}
