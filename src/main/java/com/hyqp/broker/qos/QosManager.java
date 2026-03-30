package com.hyqp.broker.qos;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Manages QoS state: message ID generation, pending message tracking,
 * and retry logic for QoS 1 and 2.
 */
public class QosManager {

    private static final Logger LOG = Logger.getLogger(QosManager.class.getName());
    private static final long RETRY_TIMEOUT_NANOS = 10_000_000_000L; // 10 seconds
    private static final int MAX_RETRIES = 3;

    private final AtomicLong messageIdCounter = new AtomicLong(1);

    // Per-session pending messages: messageId -> PendingMessage
    private final ConcurrentHashMap<Long, PendingMessage> pendingMessages = new ConcurrentHashMap<>();

    // Callback to re-send messages on retry
    private final Consumer<PendingMessage> retrySender;

    public QosManager(Consumer<PendingMessage> retrySender) {
        this.retrySender = retrySender;
    }

    public long nextMessageId() {
        return messageIdCounter.getAndIncrement();
    }

    /**
     * Register a message as pending acknowledgment.
     */
    public void addPending(PendingMessage msg) {
        pendingMessages.put(msg.getMessageId(), msg);
    }

    /**
     * Handle PUBACK from subscriber (QoS 1 complete).
     */
    public boolean handlePuback(long messageId) {
        PendingMessage msg = pendingMessages.remove(messageId);
        if (msg != null && msg.getQos() == QosLevel.QOS1) {
            LOG.fine(() -> "PUBACK received for message " + messageId);
            return true;
        }
        return false;
    }

    /**
     * Handle PUBREC from subscriber (QoS 2, step 2).
     * Returns the pending message so the caller can send PUBREL.
     */
    public PendingMessage handlePubrec(long messageId) {
        PendingMessage msg = pendingMessages.get(messageId);
        if (msg != null && msg.getState() == PendingMessage.State.AWAITING_PUBREC) {
            msg.setState(PendingMessage.State.AWAITING_PUBCOMP);
            LOG.fine(() -> "PUBREC received for message " + messageId + ", sending PUBREL");
            return msg;
        }
        return null;
    }

    /**
     * Handle PUBCOMP from subscriber (QoS 2, step 4 - complete).
     */
    public boolean handlePubcomp(long messageId) {
        PendingMessage msg = pendingMessages.remove(messageId);
        if (msg != null && msg.getState() == PendingMessage.State.AWAITING_PUBCOMP) {
            LOG.fine(() -> "PUBCOMP received for message " + messageId + " - QoS 2 complete");
            return true;
        }
        return false;
    }

    /**
     * Check for expired pending messages and retry.
     * Should be called periodically.
     */
    public void retryExpired() {
        for (var entry : pendingMessages.entrySet()) {
            PendingMessage msg = entry.getValue();
            if (msg.isExpired(RETRY_TIMEOUT_NANOS)) {
                if (msg.getRetryCount() >= MAX_RETRIES) {
                    LOG.warning(() -> "Message " + msg.getMessageId() + " exceeded max retries, dropping");
                    pendingMessages.remove(entry.getKey());
                } else {
                    msg.incrementRetry();
                    LOG.fine(() -> "Retrying message " + msg.getMessageId() + " (attempt " + msg.getRetryCount() + ")");
                    retrySender.accept(msg);
                }
            }
        }
    }

    /**
     * Clear all pending messages (on disconnect).
     */
    public void clearAll() {
        pendingMessages.clear();
    }

    public int pendingCount() {
        return pendingMessages.size();
    }

    public ConcurrentHashMap<Long, PendingMessage> getPendingMessages() {
        return pendingMessages;
    }
}
