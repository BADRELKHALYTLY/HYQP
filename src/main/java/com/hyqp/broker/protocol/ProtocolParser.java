package com.hyqp.broker.protocol;

import com.hyqp.broker.qos.QosLevel;
import com.hyqp.broker.topic.TopicMatcher;

/**
 * Stateless parser for the HYQP text protocol.
 *
 * Wire format (UTF-8, newline-delimited):
 *   CONNECT <clientName> [PERSISTENT]
 *   CREATE <topic> [schema_json]
 *   SUBSCRIBE <topic> [FILTER <filter_json>]
 *   UNSUBSCRIBE <topic>
 *   PUBLISH [RETAIN] [QOS1|QOS2] <topic> <payload>
 *   DISCONNECT
 *   PUBACK <messageId>
 *   PUBREC <messageId>
 *   PUBREL <messageId>
 *   PUBCOMP <messageId>
 */
public final class ProtocolParser {

    private ProtocolParser() {}

    public static Command parse(String line) throws ProtocolException {
        if (line == null || line.isBlank()) {
            throw new ProtocolException("Empty command");
        }

        String trimmed = line.strip();
        int firstSpace = trimmed.indexOf(' ');
        String verb = (firstSpace == -1) ? trimmed : trimmed.substring(0, firstSpace);
        String rest = (firstSpace == -1) ? "" : trimmed.substring(firstSpace + 1).strip();

        CommandType type;
        try {
            type = CommandType.valueOf(verb.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("Unknown command: " + verb);
        }

        return switch (type) {
            case CONNECT -> parseConnect(rest);
            case CREATE -> parseCreate(rest);
            case SUBSCRIBE -> parseSubscribe(rest);
            case UNSUBSCRIBE -> parseUnsubscribe(rest);
            case PUBLISH -> parsePublish(rest);
            case DISCONNECT -> parseDisconnect(rest);
            case PUBACK -> parseQosAck(rest, CommandType.PUBACK);
            case PUBREC -> parseQosAck(rest, CommandType.PUBREC);
            case PUBREL -> parseQosAck(rest, CommandType.PUBREL);
            case PUBCOMP -> parseQosAck(rest, CommandType.PUBCOMP);
        };
    }

    private static Command parseConnect(String rest) throws ProtocolException {
        if (rest.isEmpty()) throw new ProtocolException("CONNECT requires <clientName>");
        // Check for PERSISTENT flag
        if (rest.toUpperCase().endsWith(" PERSISTENT")) {
            String name = rest.substring(0, rest.length() - 11).strip();
            if (name.isEmpty()) throw new ProtocolException("CONNECT requires <clientName>");
            if (name.contains(" ")) throw new ProtocolException("Client name must not contain spaces");
            return Command.connectPersistent(name);
        }
        if (rest.contains(" ")) throw new ProtocolException("Client name must not contain spaces");
        return Command.connect(rest);
    }

    private static Command parseCreate(String rest) throws ProtocolException {
        if (rest.isEmpty()) throw new ProtocolException("CREATE requires <topic> [schema_json]");
        int spaceIdx = rest.indexOf(' ');
        if (spaceIdx == -1) {
            return Command.create(rest);
        }
        String topic = rest.substring(0, spaceIdx);
        String schema = rest.substring(spaceIdx + 1).strip();
        if (schema.isEmpty()) return Command.create(topic);
        return Command.createWithSchema(topic, schema);
    }

    private static Command parseSubscribe(String rest) throws ProtocolException {
        if (rest.isEmpty()) throw new ProtocolException("SUBSCRIBE requires <topic>");
        int filterIdx = rest.toUpperCase().indexOf(" FILTER ");
        if (filterIdx != -1) {
            String topic = rest.substring(0, filterIdx).strip();
            String filterJson = rest.substring(filterIdx + 8).strip();
            if (topic.isEmpty()) throw new ProtocolException("SUBSCRIBE requires <topic>");
            if (topic.contains(" ")) throw new ProtocolException("Topic must not contain spaces");
            if (filterJson.isEmpty()) throw new ProtocolException("FILTER requires a JSON expression");
            validateRegexLevels(topic);
            return Command.subscribeWithFilter(topic, filterJson);
        }
        if (rest.contains(" ")) throw new ProtocolException("Topic must not contain spaces");
        validateRegexLevels(rest);
        return Command.subscribe(rest);
    }

    private static Command parseUnsubscribe(String rest) throws ProtocolException {
        if (rest.isEmpty()) throw new ProtocolException("UNSUBSCRIBE requires <topic>");
        if (rest.contains(" ")) throw new ProtocolException("Topic must not contain spaces");
        return Command.unsubscribe(rest);
    }

    /**
     * Parse: PUBLISH [RETAIN] [QOS0|QOS1|QOS2] <topic> <payload>
     */
    private static Command parsePublish(String rest) throws ProtocolException {
        if (rest.isEmpty()) throw new ProtocolException("PUBLISH requires <topic> <payload>");

        boolean retain = false;
        QosLevel qos = QosLevel.QOS0;
        String remaining = rest;

        // Parse optional RETAIN flag
        if (remaining.toUpperCase().startsWith("RETAIN ")) {
            retain = true;
            remaining = remaining.substring(7).strip();
        }

        // Parse optional QoS level
        String upper = remaining.toUpperCase();
        if (upper.startsWith("QOS0 ")) {
            qos = QosLevel.QOS0;
            remaining = remaining.substring(5).strip();
        } else if (upper.startsWith("QOS1 ")) {
            qos = QosLevel.QOS1;
            remaining = remaining.substring(5).strip();
        } else if (upper.startsWith("QOS2 ")) {
            qos = QosLevel.QOS2;
            remaining = remaining.substring(5).strip();
        }

        int spaceIdx = remaining.indexOf(' ');
        if (spaceIdx == -1) throw new ProtocolException("PUBLISH requires <topic> <payload>");
        String topic = remaining.substring(0, spaceIdx);
        String payload = remaining.substring(spaceIdx + 1);
        if (payload.isBlank()) throw new ProtocolException("PUBLISH payload must not be empty");
        validateRegexLevels(topic);
        return Command.publish(topic, payload, qos, retain);
    }

    private static Command parseDisconnect(String rest) throws ProtocolException {
        if (!rest.isEmpty()) throw new ProtocolException("DISCONNECT takes no arguments");
        return Command.disconnect();
    }

    private static Command parseQosAck(String rest, CommandType type) throws ProtocolException {
        if (rest.isEmpty()) throw new ProtocolException(type + " requires <messageId>");
        try {
            long msgId = Long.parseLong(rest.strip());
            return switch (type) {
                case PUBACK -> Command.puback(msgId);
                case PUBREC -> Command.pubrec(msgId);
                case PUBREL -> Command.pubrel(msgId);
                case PUBCOMP -> Command.pubcomp(msgId);
                default -> throw new ProtocolException("Invalid QoS command");
            };
        } catch (NumberFormatException e) {
            throw new ProtocolException("Invalid messageId: " + rest);
        }
    }

    private static void validateRegexLevels(String topic) throws ProtocolException {
        for (String level : topic.split("/", -1)) {
            if (TopicMatcher.isRegexLevel(level)) {
                String error = TopicMatcher.validateRegex(level);
                if (error != null) {
                    throw new ProtocolException(error);
                }
            }
        }
    }
}
