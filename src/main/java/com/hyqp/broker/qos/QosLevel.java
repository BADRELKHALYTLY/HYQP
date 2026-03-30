package com.hyqp.broker.qos;

public enum QosLevel {
    QOS0(0),  // fire-and-forget
    QOS1(1),  // at-least-once (PUBACK)
    QOS2(2);  // exactly-once (PUBREC/PUBREL/PUBCOMP)

    private final int value;

    QosLevel(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public static QosLevel fromInt(int v) {
        return switch (v) {
            case 0 -> QOS0;
            case 1 -> QOS1;
            case 2 -> QOS2;
            default -> throw new IllegalArgumentException("Invalid QoS: " + v);
        };
    }

    public static QosLevel fromString(String s) {
        return switch (s.toUpperCase()) {
            case "QOS0", "0" -> QOS0;
            case "QOS1", "1" -> QOS1;
            case "QOS2", "2" -> QOS2;
            default -> throw new IllegalArgumentException("Invalid QoS: " + s);
        };
    }
}
