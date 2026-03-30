package com.hyqp.broker.qos;

/**
 * Represents a message awaiting acknowledgment from a subscriber.
 */
public class PendingMessage {

    public enum State {
        AWAITING_PUBACK,    // QoS 1: waiting for PUBACK
        AWAITING_PUBREC,    // QoS 2 step 1: waiting for PUBREC
        AWAITING_PUBCOMP    // QoS 2 step 3: waiting for PUBCOMP (after PUBREL sent)
    }

    private final long messageId;
    private final String topic;
    private final String payload;
    private final QosLevel qos;
    private final long createdAtNanos;
    private volatile State state;
    private volatile int retryCount;

    public PendingMessage(long messageId, String topic, String payload, QosLevel qos) {
        this.messageId = messageId;
        this.topic = topic;
        this.payload = payload;
        this.qos = qos;
        this.createdAtNanos = System.nanoTime();
        this.state = qos == QosLevel.QOS1 ? State.AWAITING_PUBACK : State.AWAITING_PUBREC;
        this.retryCount = 0;
    }

    public long getMessageId() { return messageId; }
    public String getTopic() { return topic; }
    public String getPayload() { return payload; }
    public QosLevel getQos() { return qos; }
    public State getState() { return state; }
    public int getRetryCount() { return retryCount; }
    public long getCreatedAtNanos() { return createdAtNanos; }

    public void setState(State state) { this.state = state; }
    public void incrementRetry() { this.retryCount++; }

    public boolean isExpired(long timeoutNanos) {
        return (System.nanoTime() - createdAtNanos) > timeoutNanos;
    }
}
