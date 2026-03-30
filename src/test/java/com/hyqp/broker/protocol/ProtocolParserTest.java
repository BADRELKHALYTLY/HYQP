package com.hyqp.broker.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolParserTest {

    @Test
    void parseConnect() throws ProtocolException {
        Command cmd = ProtocolParser.parse("CONNECT client1");
        assertEquals(CommandType.CONNECT, cmd.type());
        assertEquals("client1", cmd.clientName());
        assertNull(cmd.topic());
        assertNull(cmd.payload());
    }

    @Test
    void parseConnectCaseInsensitive() throws ProtocolException {
        Command cmd = ProtocolParser.parse("connect myClient");
        assertEquals(CommandType.CONNECT, cmd.type());
        assertEquals("myClient", cmd.clientName());
    }

    @Test
    void parseConnectMissingName() {
        assertThrows(ProtocolException.class, () -> ProtocolParser.parse("CONNECT"));
    }

    @Test
    void parseConnectNameWithSpaces() {
        assertThrows(ProtocolException.class, () -> ProtocolParser.parse("CONNECT my client"));
    }

    @Test
    void parseSubscribe() throws ProtocolException {
        Command cmd = ProtocolParser.parse("SUBSCRIBE sensors/temperature");
        assertEquals(CommandType.SUBSCRIBE, cmd.type());
        assertEquals("sensors/temperature", cmd.topic());
    }

    @Test
    void parseSubscribeMissingTopic() {
        assertThrows(ProtocolException.class, () -> ProtocolParser.parse("SUBSCRIBE"));
    }

    @Test
    void parseUnsubscribe() throws ProtocolException {
        Command cmd = ProtocolParser.parse("UNSUBSCRIBE sensors/temperature");
        assertEquals(CommandType.UNSUBSCRIBE, cmd.type());
        assertEquals("sensors/temperature", cmd.topic());
    }

    @Test
    void parsePublish() throws ProtocolException {
        Command cmd = ProtocolParser.parse("PUBLISH sensors/temp {\"value\": 22.5}");
        assertEquals(CommandType.PUBLISH, cmd.type());
        assertEquals("sensors/temp", cmd.topic());
        assertEquals("{\"value\": 22.5}", cmd.payload());
    }

    @Test
    void parsePublishPayloadWithSpaces() throws ProtocolException {
        Command cmd = ProtocolParser.parse("PUBLISH topic hello world foo");
        assertEquals("hello world foo", cmd.payload());
    }

    @Test
    void parsePublishMissingPayload() {
        assertThrows(ProtocolException.class, () -> ProtocolParser.parse("PUBLISH topic"));
    }

    @Test
    void parsePublishMissingTopicAndPayload() {
        assertThrows(ProtocolException.class, () -> ProtocolParser.parse("PUBLISH"));
    }

    @Test
    void parseDisconnect() throws ProtocolException {
        Command cmd = ProtocolParser.parse("DISCONNECT");
        assertEquals(CommandType.DISCONNECT, cmd.type());
        assertNull(cmd.topic());
    }

    @Test
    void parseDisconnectWithArgs() {
        assertThrows(ProtocolException.class, () -> ProtocolParser.parse("DISCONNECT now"));
    }

    @Test
    void parseEmptyLine() {
        assertThrows(ProtocolException.class, () -> ProtocolParser.parse(""));
    }

    @Test
    void parseNull() {
        assertThrows(ProtocolException.class, () -> ProtocolParser.parse(null));
    }

    @Test
    void parseUnknownCommand() {
        assertThrows(ProtocolException.class, () -> ProtocolParser.parse("PING"));
    }

    @Test
    void parseWithLeadingTrailingSpaces() throws ProtocolException {
        Command cmd = ProtocolParser.parse("  SUBSCRIBE  topic1  ");
        assertEquals(CommandType.SUBSCRIBE, cmd.type());
        assertEquals("topic1", cmd.topic());
    }
}
