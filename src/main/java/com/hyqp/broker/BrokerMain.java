package com.hyqp.broker;

import com.hyqp.broker.dispatch.MessageDispatcher;
import com.hyqp.broker.persistence.RetainedStore;
import com.hyqp.broker.persistence.SessionStore;
import com.hyqp.broker.server.Broker;
import com.hyqp.broker.topic.TopicRegistry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Entry point for the HYQP broker.
 *
 * Usage: java -cp ... com.hyqp.broker.BrokerMain [port]
 * Default port: 4444
 */
public class BrokerMain {

    private static final Logger LOG = Logger.getLogger(BrokerMain.class.getName());
    private static final int DEFAULT_PORT = 4444;

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port: " + args[0] + ". Using default " + DEFAULT_PORT);
            }
        }

        TopicRegistry registry = new TopicRegistry();
        MessageDispatcher dispatcher = new MessageDispatcher(registry);
        RetainedStore retainedStore = new RetainedStore(Path.of("data/retained"));
        SessionStore sessionStore = new SessionStore(Path.of("data/sessions"));
        Broker broker = new Broker(port, registry, dispatcher, retainedStore, sessionStore);

        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(broker::stop));

        try {
            broker.start();
        } catch (IOException e) {
            LOG.severe("Failed to start broker: " + e.getMessage());
            System.exit(1);
        }
    }
}
