package com.hyqp.broker.dispatch;

import com.hyqp.broker.qos.QosLevel;
import com.hyqp.broker.server.ClientSession;
import com.hyqp.broker.topic.TopicMatcher;
import com.hyqp.broker.topic.TopicRegistry;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

public class MessageDispatcher {

    private static final Logger LOG = Logger.getLogger(MessageDispatcher.class.getName());

    private final TopicRegistry registry;
    private final List<MessageFilter> filters = new CopyOnWriteArrayList<>();

    public MessageDispatcher(TopicRegistry registry) {
        this.registry = registry;
    }

    public void dispatch(String topic, String payload, ClientSession sender, QosLevel qos) {
        if (TopicMatcher.isWildcard(topic)) {
            Set<String> concreteTopics = registry.getConcreteTopicsMatching(topic);
            Set<ClientSession> alreadySent = new HashSet<>();
            for (String concreteTopic : concreteTopics) {
                for (ClientSession subscriber : registry.getSubscribers(concreteTopic)) {
                    if (subscriber == sender) continue;
                    if (!subscriber.isActive()) continue;
                    if (alreadySent.contains(subscriber)) continue;

                    if (passesFilters(concreteTopic, payload, subscriber)
                            && subscriber.acceptsPayload(concreteTopic, payload)) {
                        subscriber.sendWithQos(concreteTopic, payload, qos);
                        alreadySent.add(subscriber);
                    }
                }
            }
        } else {
            Set<ClientSession> subscribers = registry.getSubscribers(topic);
            for (ClientSession subscriber : subscribers) {
                if (subscriber == sender) continue;
                if (!subscriber.isActive()) continue;

                if (passesFilters(topic, payload, subscriber)
                        && subscriber.acceptsPayload(topic, payload)) {
                    subscriber.sendWithQos(topic, payload, qos);
                }
            }
        }
    }

    /**
     * Backward-compatible dispatch with QoS 0.
     */
    public void dispatch(String topic, String payload, ClientSession sender) {
        dispatch(topic, payload, sender, QosLevel.QOS0);
    }

    private boolean passesFilters(String topic, String payload, ClientSession session) {
        for (MessageFilter filter : filters) {
            if (!filter.accepts(topic, payload, session)) {
                return false;
            }
        }
        return true;
    }

    public void addFilter(MessageFilter filter) { filters.add(filter); }
    public void removeFilter(MessageFilter filter) { filters.remove(filter); }
}
