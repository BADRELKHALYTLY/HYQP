package com.hyqp.broker.protocol;

public enum CommandType {
    CONNECT,
    CREATE,
    SUBSCRIBE,
    UNSUBSCRIBE,
    PUBLISH,
    DISCONNECT,
    PUBACK,      // QoS 1: subscriber acknowledges message
    PUBREC,      // QoS 2 step 2: subscriber received
    PUBREL,      // QoS 2 step 3: broker/subscriber release
    PUBCOMP      // QoS 2 step 4: complete
}
