package com.hyqp.broker.benchmark;

import com.rabbitmq.client.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

/**
 * Benchmark client for AMQP using RabbitMQ Java client.
 * Uses topic exchange for pub/sub semantics.
 */
public class AmqpBenchmarkClient {

    private static final String EXCHANGE_NAME = "hyqp_bench";

    private final Connection connection;
    private final Channel channel;
    private String queueName;

    public AmqpBenchmarkClient(String host, int port) throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        this.connection = factory.newConnection();
        this.channel = connection.createChannel();
        channel.exchangeDeclare(EXCHANGE_NAME, BuiltinExchangeType.TOPIC, false, true, null);
    }

    public void subscribe(String routingKey, BiConsumer<String, String> onMessage) throws IOException {
        queueName = channel.queueDeclare().getQueue();
        channel.queueBind(queueName, EXCHANGE_NAME, routingKey);

        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String payload = new String(delivery.getBody(), StandardCharsets.UTF_8);
            String topic = delivery.getEnvelope().getRoutingKey();
            onMessage.accept(topic, payload);
        };
        channel.basicConsume(queueName, true, deliverCallback, consumerTag -> {});
    }

    public void publish(String routingKey, String payload) throws IOException {
        channel.basicPublish(EXCHANGE_NAME, routingKey, null,
                payload.getBytes(StandardCharsets.UTF_8));
    }

    public void disconnect() {
        try {
            if (channel != null && channel.isOpen()) channel.close();
            if (connection != null && connection.isOpen()) connection.close();
        } catch (Exception ignored) {}
    }

    public static String buildPayload(int seq, int targetBytes) {
        return BenchmarkClient.buildPayload(seq, targetBytes);
    }

    public static long extractTimestamp(String payload) {
        return BenchmarkClient.extractTimestamp(payload);
    }
}
