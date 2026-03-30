package com.hyqp.broker.dispatch;

import com.hyqp.broker.server.ClientSession;

/**
 * Extensibility point for intelligent message filtering.
 * Implementations can inspect topic, payload, and session metadata
 * to decide whether a message should be delivered.
 */
@FunctionalInterface
public interface MessageFilter {

    /**
     * @return true if the message should be delivered to the given session
     */
    boolean accepts(String topic, String payload, ClientSession session);
}
