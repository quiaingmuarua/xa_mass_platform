package com.xa.mass.starter;

import com.xa.mass.base.channel.messaging.api.MessageQueue;
import com.xa.mass.base.channel.messaging.memory.InMemoryMessageQueue;
import com.xa.mass.gateway.queue.OutboundDelivery;
import com.xa.mass.starter.builder.MassApplicationBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Small bootstrap examples for the embedded runtime builder.
 */
public class MassApplicationExample {

    private static final Logger logger = LoggerFactory.getLogger(MassApplicationExample.class);

    public static void main(String[] args) {
        logger.info("Mass Application example bootstrap");

        exampleDevelopmentMode();
        exampleProductionMode();
        exampleTestMode();
        exampleCustomConfiguration();
        exampleGatewayOnly();
        exampleEngineOnly();
    }

    private static void exampleDevelopmentMode() {
        logger.info("Example 1: development mode");
        MassApplication app = MassApplicationBuilder.createDevelopment(8080);
        app.start();
        app.stop();
    }

    private static void exampleProductionMode() {
        logger.info("Example 2: production mode");
        MassApplication app = MassApplicationBuilder.createProduction(8080);
        app.start();
        app.stop();
    }

    private static void exampleTestMode() {
        logger.info("Example 3: test mode");
        MassApplication app = MassApplicationBuilder.createTest(8080);
        app.start();
        app.stop();
    }

    private static void exampleCustomConfiguration() {
        logger.info("Example 4: custom configuration");
        MessageQueue<String> inputQueue = new InMemoryMessageQueue<>("WsInputRawJson", String.class);
        MessageQueue<OutboundDelivery> outputQueue = new InMemoryMessageQueue<>("WsOutboundDelivery", OutboundDelivery.class);

        MassApplication app = MassApplicationBuilder.create()
                .server(9090, "/custom-ws")
                .gateway(gateway -> gateway
                        .enabled(true)
                        .maxConnections(2000)
                        .inputQueue(inputQueue)
                        .outputQueue(outputQueue)
                        .queueMode())
                .engine(engine -> engine
                        .enabled(true)
                        .workerThreads(12))
                .build();

        app.start();
        app.stop();
    }

    private static void exampleGatewayOnly() {
        logger.info("Example 5: gateway only");
        MessageQueue<String> inputQueue = new InMemoryMessageQueue<>("WsInputRawJson", String.class);
        MessageQueue<OutboundDelivery> outputQueue = new InMemoryMessageQueue<>("WsOutboundDelivery", OutboundDelivery.class);

        MassApplication app = MassApplicationBuilder.create()
                .server(8080)
                .gateway(gateway -> gateway
                        .enabled(true)
                        .maxConnections(100)
                        .inputQueue(inputQueue)
                        .outputQueue(outputQueue))
                .engine(engine -> engine.enabled(false))
                .build();

        app.start();
        app.stop();
    }

    private static void exampleEngineOnly() {
        logger.info("Example 6: engine only");
        MassApplication app = MassApplicationBuilder.create()
                .server(8080)
                .gateway(gateway -> gateway.enabled(false))
                .engine(engine -> engine
                        .enabled(true)
                        .workerThreads(4))
                .build();

        app.start();
        app.stop();
    }
}
