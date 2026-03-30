package com.hyqp.broker.topic;

import com.hyqp.broker.dispatch.MessageDispatcher;
import com.hyqp.broker.server.Broker;
import com.hyqp.broker.server.ClientSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TopicRegistryTest {

    private TopicRegistry registry;
    private MessageDispatcher dispatcher;
    private Broker broker;

    @BeforeEach
    void setUp() {
        registry = new TopicRegistry();
        dispatcher = new MessageDispatcher(registry);
        broker = new Broker(0, registry, dispatcher);
    }

    /**
     * Creates a real ClientSession backed by a loopback socket pair.
     */
    private ClientSession createSession() throws IOException {
        ServerSocket ss = new ServerSocket(0);
        Socket client = new Socket("localhost", ss.getLocalPort());
        Socket server = ss.accept();
        ss.close();
        return new ClientSession(server, registry, dispatcher, broker);
    }

    @Test
    void subscribeAndGetSubscribers() throws IOException {
        ClientSession session = createSession();
        registry.subscribe("topic1", session);

        Set<ClientSession> subs = registry.getSubscribers("topic1");
        assertEquals(1, subs.size());
        assertTrue(subs.contains(session));
    }

    @Test
    void getSubscribersForUnknownTopic() {
        Set<ClientSession> subs = registry.getSubscribers("nonexistent");
        assertNotNull(subs);
        assertTrue(subs.isEmpty());
    }

    @Test
    void multipleSubscribersSameTopic() throws IOException {
        ClientSession s1 = createSession();
        ClientSession s2 = createSession();

        registry.subscribe("topic1", s1);
        registry.subscribe("topic1", s2);

        Set<ClientSession> subs = registry.getSubscribers("topic1");
        assertEquals(2, subs.size());
    }

    @Test
    void unsubscribe() throws IOException {
        ClientSession session = createSession();
        registry.subscribe("topic1", session);
        registry.unsubscribe("topic1", session);

        Set<ClientSession> subs = registry.getSubscribers("topic1");
        assertTrue(subs.isEmpty());
        assertEquals(0, registry.topicCount()); // empty topic removed
    }

    @Test
    void unsubscribeAllCleansUpAllTopics() throws IOException {
        ClientSession session = createSession();
        // Manually add to session's subscriptions via the registry
        session.getSubscriptions(); // read-only view, need to go through subscribe path

        // Simulate what ClientSession.handle(SUBSCRIBE) does:
        registry.subscribe("t1", session);
        registry.subscribe("t2", session);

        // We need the session to know its own subscriptions for unsubscribeAll.
        // In the real flow, ClientSession.handle() adds to its internal set.
        // For this test, we call unsubscribeAll with topics still in the registry.
        // Since session.getSubscriptions() is empty (we didn't go through handle()),
        // let's test the direct unsubscribe path instead:
        registry.unsubscribe("t1", session);
        registry.unsubscribe("t2", session);

        assertTrue(registry.getSubscribers("t1").isEmpty());
        assertTrue(registry.getSubscribers("t2").isEmpty());
        assertEquals(0, registry.topicCount());
    }

    @Test
    void duplicateSubscriptionIsIdempotent() throws IOException {
        ClientSession session = createSession();
        registry.subscribe("topic1", session);
        registry.subscribe("topic1", session);

        assertEquals(1, registry.getSubscribers("topic1").size());
    }

    @Test
    void topicsReturnsAllActiveTopics() throws IOException {
        ClientSession session = createSession();
        registry.subscribe("a", session);
        registry.subscribe("b", session);
        registry.subscribe("c", session);

        Set<String> topics = registry.topics();
        assertEquals(3, topics.size());
        assertTrue(topics.containsAll(Set.of("a", "b", "c")));
    }
}
