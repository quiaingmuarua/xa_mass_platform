package com.xa.mass.starter;

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

        exampleExplicitRealtimeRuntime();
        exampleDualRealtimeAdapters();
        exampleQueueBackedTransport();
        exampleSingleRealtimeAdapter();
        exampleEngineOnly();
    }

    private static void exampleExplicitRealtimeRuntime() {
        logger.info("Example 1: explicit realtime runtime");
        MassApplication app = MassApplicationBuilder.create()
                .transport(transport -> transport
                        .webSocketAdapter(webSocket -> webSocket
                                .server(8080)
                                .enabled(true)
                                .maxConnections(1000)))
                .engine(engine -> engine
                        .enabled(true)
                        .workerThreads(8))
                .build();

        app.start();
        app.stop();
    }

    private static void exampleDualRealtimeAdapters() {
        logger.info("Example 2: multiple realtime adapter instances");
        MassApplication app = MassApplicationBuilder.create()
                .transport(transport -> transport
                        .webSocketAdapter(webSocket -> webSocket
                                .adapterId("ws-public")
                                .server(8081, "/ws")
                                .enabled(true)
                                .maxConnections(1000))
                        .addWebSocketAdapter(webSocket -> webSocket
                                .adapterId("ws-internal")
                                .server(8083, "/internal-ws")
                                .enabled(true)
                                .serverEnabled(true)
                                .maxConnections(200))
                        .socketAdapter(socket -> socket
                                .adapterId("socket-edge")
                                .server(8082)
                                .enabled(true)
                                .serverEnabled(true)
                                .maxConnections(1000)))
                .engine(engine -> engine
                        .enabled(true)
                        .workerThreads(12))
                .build();

        app.start();
        app.stop();
    }

    private static void exampleQueueBackedTransport() {
        logger.info("Example 3: queue-backed transport");
        MassApplication app = MassApplicationBuilder.create()
                .transport(transport -> transport
                        .webSocketAdapter(webSocket -> webSocket
                                .server(9090, "/custom-ws")
                                .enabled(true)
                                .maxConnections(2000)))
                .engine(engine -> engine
                        .enabled(true)
                        .workerThreads(12))
                .build();

        app.start();
        app.stop();
    }

    private static void exampleSingleRealtimeAdapter() {
        logger.info("Example 4: single realtime adapter");
        MassApplication app = MassApplicationBuilder.create()
                .transport(transport -> transport
                        .webSocketAdapter(webSocket -> webSocket
                                .adapterId("ws-default")
                                .server(8080)
                                .enabled(true)
                                .maxConnections(100)))
                .engine(engine -> engine.enabled(false))
                .build();

        app.start();
        app.stop();
    }

    private static void exampleEngineOnly() {
        logger.info("Example 5: engine only");
        MassApplication app = MassApplicationBuilder.create()
                .transport(transport -> transport
                        .webSocketAdapter(webSocket -> webSocket.server(8080).enabled(false).serverEnabled(false)))
                .engine(engine -> engine
                        .enabled(true)
                        .workerThreads(4))
                .build();

        app.start();
        app.stop();
    }
}

