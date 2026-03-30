package com.hyqp.broker.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Stores the last retained message per topic.
 * New subscribers receive the retained message immediately upon subscribing.
 * Publishing an empty retained message clears the retained entry.
 *
 * Persistence: retained messages are written to disk and restored on broker startup.
 */
public class RetainedStore {

    private static final Logger LOG = Logger.getLogger(RetainedStore.class.getName());

    private final ConcurrentHashMap<String, String> retained = new ConcurrentHashMap<>();
    private final Path dataDir;

    public RetainedStore(Path dataDir) {
        this.dataDir = dataDir;
        loadFromDisk();
    }

    /**
     * Store a retained message. Empty payload removes the retained message.
     */
    public void setRetained(String topic, String payload) {
        if (payload == null || payload.isBlank()) {
            retained.remove(topic);
            deleteFromDisk(topic);
            LOG.fine(() -> "Retained message cleared for " + topic);
        } else {
            retained.put(topic, payload);
            saveToDisk(topic, payload);
            LOG.fine(() -> "Retained message stored for " + topic);
        }
    }

    /**
     * Get the retained message for a topic, or null if none.
     */
    public String getRetained(String topic) {
        return retained.get(topic);
    }

    /**
     * Get all topics that have retained messages.
     */
    public Set<String> getRetainedTopics() {
        return Collections.unmodifiableSet(retained.keySet());
    }

    /**
     * Get all retained entries (for matching wildcard subscriptions).
     */
    public Map<String, String> getAllRetained() {
        return Collections.unmodifiableMap(retained);
    }

    private void saveToDisk(String topic, String payload) {
        try {
            Path file = dataDir.resolve(topicToFilename(topic));
            Files.createDirectories(file.getParent());
            Files.writeString(file, topic + "\n" + payload);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to persist retained message for " + topic, e);
        }
    }

    private void deleteFromDisk(String topic) {
        try {
            Path file = dataDir.resolve(topicToFilename(topic));
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.log(Level.FINE, "Failed to delete retained file for " + topic, e);
        }
    }

    private void loadFromDisk() {
        if (!Files.exists(dataDir)) return;
        try (var stream = Files.walk(dataDir)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.toString().endsWith(".retained"))
                  .forEach(this::loadFile);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to load retained messages from disk", e);
        }
        LOG.info(() -> "Loaded " + retained.size() + " retained messages from disk");
    }

    private void loadFile(Path file) {
        try {
            String content = Files.readString(file);
            int nl = content.indexOf('\n');
            if (nl > 0) {
                String topic = content.substring(0, nl);
                String payload = content.substring(nl + 1);
                retained.put(topic, payload);
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "Failed to read retained file " + file, e);
        }
    }

    private String topicToFilename(String topic) {
        return topic.replace('/', '_').replace('\\', '_') + ".retained";
    }
}
