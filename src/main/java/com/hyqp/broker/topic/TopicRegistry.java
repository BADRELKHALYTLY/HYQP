package com.hyqp.broker.topic;

import com.hyqp.broker.schema.JsonValue;
import com.hyqp.broker.server.ClientSession;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Thread-safe subscription index.
 * Maps topic names to the set of sessions subscribed to each topic.
 *
 * Uses CopyOnWriteArraySet because subscriptions change rarely (sub/unsub)
 * relative to reads (every publish fans out through the subscriber set).
 */
public class TopicRegistry {

    private final ConcurrentHashMap<String, Set<ClientSession>> subscriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, JsonValue> schemas = new ConcurrentHashMap<>();

    /**
     * Creates a topic without schema. Returns true if newly created.
     */
    public boolean createTopic(String topic) {
        Set<ClientSession> existing = subscriptions.putIfAbsent(topic, new CopyOnWriteArraySet<>());
        return existing == null;
    }

    /**
     * Creates a topic with a JSON schema for payload validation.
     * Returns true if newly created.
     */
    public boolean createTopic(String topic, JsonValue schema) {
        Set<ClientSession> existing = subscriptions.putIfAbsent(topic, new CopyOnWriteArraySet<>());
        if (existing == null) {
            if (schema != null) {
                schemas.put(topic, schema);
            }
            return true;
        }
        return false;
    }

    /**
     * Returns the schema for a topic, or null if no schema is defined.
     */
    public JsonValue getSchema(String topic) {
        return schemas.get(topic);
    }

    public boolean topicExists(String topic) {
        return subscriptions.containsKey(topic);
    }

    public void subscribe(String topic, ClientSession session) {
        subscriptions.computeIfAbsent(topic, k -> new CopyOnWriteArraySet<>()).add(session);
    }

    public void unsubscribe(String topic, ClientSession session) {
        subscriptions.computeIfPresent(topic, (k, sessions) -> {
            sessions.remove(session);
            return sessions.isEmpty() ? null : sessions;  // remove empty topics
        });
    }

    public void unsubscribeAll(ClientSession session) {
        for (String topic : session.getSubscriptions()) {
            unsubscribe(topic, session);
        }
    }

    /**
     * Returns all sessions subscribed to the given concrete topic,
     * including those subscribed via wildcard filters (+ and #).
     */
    public Set<ClientSession> getSubscribers(String topic) {
        Set<ClientSession> result = new CopyOnWriteArraySet<>();

        for (var entry : subscriptions.entrySet()) {
            String filter = entry.getKey();
            if (filter.equals(topic) || TopicMatcher.matches(filter, topic)) {
                result.addAll(entry.getValue());
            }
        }

        return result;
    }

    /**
     * Returns all concrete (non-wildcard) topic names that match the given pattern.
     * Used for wildcard publishing: PUBLISH sensors/# broadcasts to all
     * existing topics under sensors/.
     */
    public Set<String> getConcreteTopicsMatching(String pattern) {
        Set<String> matched = ConcurrentHashMap.newKeySet();
        for (String topic : subscriptions.keySet()) {
            if (!TopicMatcher.isWildcard(topic) && TopicMatcher.matches(pattern, topic)) {
                matched.add(topic);
            }
        }
        return matched;
    }

    public int topicCount() {
        return subscriptions.size();
    }

    public Set<String> topics() {
        return Collections.unmodifiableSet(subscriptions.keySet());
    }
}
