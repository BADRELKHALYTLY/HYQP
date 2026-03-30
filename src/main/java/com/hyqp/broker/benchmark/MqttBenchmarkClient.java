package com.hyqp.broker.benchmark;

import org.eclipse.paho.client.mqttv3.*;

import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

/**
 * Benchmark client for MQTT using Eclipse Paho.
 * Targets Mosquitto broker on localhost:1883.
 */
public class MqttBenchmarkClient {

    private final String clientId;
    private final MqttClient client;

    public MqttBenchmarkClient(String clientId, String host, int port) throws MqttException {
        this.clientId = clientId;
        String broker = "tcp://" + host + ":" + port;
        this.client = new MqttClient(broker, clientId, null); // null = no persistence
    }

    public void connect() throws MqttException {
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        opts.setConnectionTimeout(10);
        client.connect(opts);
    }

    public void subscribe(String topic, BiConsumer<String, String> onMessage) throws MqttException {
        client.subscribe(topic, 0, (t, msg) -> {
            String payload = new String(msg.getPayload(), StandardCharsets.UTF_8);
            onMessage.accept(t, payload);
        });
    }

    public void publish(String topic, String payload) throws MqttException {
        MqttMessage msg = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
        msg.setQos(0); // fire-and-forget, same as HYQP
        client.publish(topic, msg);
    }

    public void disconnect() {
        try {
            if (client.isConnected()) {
                client.disconnect(2000);
            }
            client.close();
        } catch (MqttException ignored) {}
    }

    public static String buildPayload(int seq, int targetBytes) {
        return BenchmarkClient.buildPayload(seq, targetBytes);
    }

    public static long extractTimestamp(String payload) {
        return BenchmarkClient.extractTimestamp(payload);
    }
}
