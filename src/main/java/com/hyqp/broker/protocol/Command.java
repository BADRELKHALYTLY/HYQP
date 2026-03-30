package com.hyqp.broker.protocol;

import com.hyqp.broker.qos.QosLevel;

/**
 * Immutable representation of a parsed protocol command.
 */
public record Command(
    CommandType type,
    String clientName,  // non-null only for CONNECT
    String topic,       // non-null for SUBSCRIBE, UNSUBSCRIBE, PUBLISH, CREATE
    String payload,     // non-null for PUBLISH, CREATE(schema), SUBSCRIBE(filter)
    QosLevel qos,       // non-null for PUBLISH with QoS
    boolean retain,     // true for PUBLISH RETAIN
    boolean persistent, // true for CONNECT PERSISTENT
    long messageId      // for PUBACK, PUBREC, PUBREL, PUBCOMP
) {

    // --- CONNECT ---
    public static Command connect(String clientName) {
        return new Command(CommandType.CONNECT, clientName, null, null, null, false, false, 0);
    }

    public static Command connectPersistent(String clientName) {
        return new Command(CommandType.CONNECT, clientName, null, null, null, false, true, 0);
    }

    // --- CREATE ---
    public static Command create(String topic) {
        return new Command(CommandType.CREATE, null, topic, null, null, false, false, 0);
    }

    public static Command createWithSchema(String topic, String schema) {
        return new Command(CommandType.CREATE, null, topic, schema, null, false, false, 0);
    }

    // --- SUBSCRIBE ---
    public static Command subscribe(String topic) {
        return new Command(CommandType.SUBSCRIBE, null, topic, null, null, false, false, 0);
    }

    public static Command subscribeWithFilter(String topic, String filter) {
        return new Command(CommandType.SUBSCRIBE, null, topic, filter, null, false, false, 0);
    }

    // --- UNSUBSCRIBE ---
    public static Command unsubscribe(String topic) {
        return new Command(CommandType.UNSUBSCRIBE, null, topic, null, null, false, false, 0);
    }

    // --- PUBLISH ---
    public static Command publish(String topic, String payload) {
        return new Command(CommandType.PUBLISH, null, topic, payload, QosLevel.QOS0, false, false, 0);
    }

    public static Command publish(String topic, String payload, QosLevel qos, boolean retain) {
        return new Command(CommandType.PUBLISH, null, topic, payload, qos, retain, false, 0);
    }

    // --- DISCONNECT ---
    public static Command disconnect() {
        return new Command(CommandType.DISCONNECT, null, null, null, null, false, false, 0);
    }

    // --- QoS handshake ---
    public static Command puback(long messageId) {
        return new Command(CommandType.PUBACK, null, null, null, null, false, false, messageId);
    }

    public static Command pubrec(long messageId) {
        return new Command(CommandType.PUBREC, null, null, null, null, false, false, messageId);
    }

    public static Command pubrel(long messageId) {
        return new Command(CommandType.PUBREL, null, null, null, null, false, false, messageId);
    }

    public static Command pubcomp(long messageId) {
        return new Command(CommandType.PUBCOMP, null, null, null, null, false, false, messageId);
    }
}
