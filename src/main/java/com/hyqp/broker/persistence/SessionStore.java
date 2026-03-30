package com.hyqp.broker.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persists session state (subscriptions and filters) for clients
 * that connect with PERSISTENT mode. On reconnection, the session
 * state is restored without requiring re-subscription.
 */
public class SessionStore {

    private static final Logger LOG = Logger.getLogger(SessionStore.class.getName());

    /**
     * Stored session: subscriptions and their associated filters.
     */
    public record StoredSession(
        String clientName,
        Map<String, String> subscriptions  // topic -> filter (null if no filter)
    ) {}

    private final ConcurrentHashMap<String, StoredSession> sessions = new ConcurrentHashMap<>();
    private final Path dataDir;

    public SessionStore(Path dataDir) {
        this.dataDir = dataDir;
        loadFromDisk();
    }

    /**
     * Save a session's subscription state.
     */
    public void saveSession(String clientName, Map<String, String> subscriptions) {
        StoredSession session = new StoredSession(clientName, new HashMap<>(subscriptions));
        sessions.put(clientName, session);
        saveToDisk(clientName, session);
        LOG.fine(() -> "Session saved for " + clientName + " (" + subscriptions.size() + " subscriptions)");
    }

    /**
     * Retrieve a stored session, or null if none exists.
     */
    public StoredSession getSession(String clientName) {
        return sessions.get(clientName);
    }

    /**
     * Remove a stored session (on clean disconnect or explicit clean session).
     */
    public void removeSession(String clientName) {
        sessions.remove(clientName);
        deleteFromDisk(clientName);
        LOG.fine(() -> "Session removed for " + clientName);
    }

    public boolean hasSession(String clientName) {
        return sessions.containsKey(clientName);
    }

    private void saveToDisk(String clientName, StoredSession session) {
        try {
            Path file = dataDir.resolve(clientName + ".session");
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("CLIENT:").append(clientName).append('\n');
            for (var entry : session.subscriptions().entrySet()) {
                sb.append("SUB:").append(entry.getKey());
                if (entry.getValue() != null) {
                    sb.append(" FILTER ").append(entry.getValue());
                }
                sb.append('\n');
            }
            Files.writeString(file, sb.toString());
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to persist session for " + clientName, e);
        }
    }

    private void deleteFromDisk(String clientName) {
        try {
            Files.deleteIfExists(dataDir.resolve(clientName + ".session"));
        } catch (IOException e) {
            LOG.log(Level.FINE, "Failed to delete session file for " + clientName, e);
        }
    }

    private void loadFromDisk() {
        if (!Files.exists(dataDir)) return;
        try (var stream = Files.list(dataDir)) {
            stream.filter(p -> p.toString().endsWith(".session"))
                  .forEach(this::loadSessionFile);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to load sessions from disk", e);
        }
        LOG.info(() -> "Loaded " + sessions.size() + " persistent sessions from disk");
    }

    private void loadSessionFile(Path file) {
        try {
            List<String> lines = Files.readAllLines(file);
            String clientName = null;
            Map<String, String> subs = new HashMap<>();
            for (String line : lines) {
                if (line.startsWith("CLIENT:")) {
                    clientName = line.substring(7);
                } else if (line.startsWith("SUB:")) {
                    String rest = line.substring(4);
                    int filterIdx = rest.indexOf(" FILTER ");
                    if (filterIdx >= 0) {
                        subs.put(rest.substring(0, filterIdx), rest.substring(filterIdx + 8));
                    } else {
                        subs.put(rest, null);
                    }
                }
            }
            if (clientName != null) {
                sessions.put(clientName, new StoredSession(clientName, subs));
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "Failed to read session file " + file, e);
        }
    }
}
