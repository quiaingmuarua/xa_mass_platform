package com.xa.mass.worker;

import com.xa.mass.worker.execution.DomainInspectHandler;
import com.xa.mass.worker.execution.PhoneInspectHandler;
import com.xa.mass.worker.execution.StringTransformHandler;
import com.xa.mass.worker.execution.WorkerCommandProcessor;
import com.xa.mass.worker.transport.polling.PollingWorkerTransport;
import com.xa.mass.worker.transport.socket.SocketWorkerTransport;
import com.xa.mass.worker.transport.websocket.WebSocketWorkerTransport;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import java.util.Map;

public final class WorkerMain {

    private WorkerMain() {
    }

    public static void main(String[] args) {
        try {
            run(WorkerConfiguration.parse(args));
        } catch (IllegalArgumentException error) {
            System.err.println(error.getMessage());
            System.exit(2);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    static void run(WorkerConfiguration configuration)
            throws InterruptedException {
        WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
        WorkerCommandProcessor processor = new WorkerCommandProcessor(
                configuration.workerId(),
                codec,
                Map.of(
                        PhoneInspectHandler.EVENT_CODE,
                        new PhoneInspectHandler(),
                        StringTransformHandler.EVENT_CODE,
                        new StringTransformHandler(),
                        DomainInspectHandler.EVENT_CODE,
                        new DomainInspectHandler()
                )
        );
        switch (configuration.transport()) {
            case POLLING -> new PollingWorkerTransport(
                    configuration.serverUrl(),
                    configuration.endpointManagerId(),
                    configuration.workerId(),
                    configuration.requestTimeout(),
                    codec,
                    processor
            ).runForever(configuration.pollInterval());
            case WEBSOCKET -> runWebSocket(configuration, codec, processor);
            case SOCKET -> runSocket(configuration, codec, processor);
        }
    }

    private static void runWebSocket(
            WorkerConfiguration configuration,
            WorkerDeliveryCodec codec,
            WorkerCommandProcessor processor
    ) throws InterruptedException {
        try (WebSocketWorkerTransport transport =
                     new WebSocketWorkerTransport(
                             configuration.serverUrl(),
                             configuration.workerId(),
                             configuration.requestTimeout(),
                             configuration.reconnectInterval(),
                             codec,
                             processor
                     )) {
            runLongLived(transport, transport::runForever);
        }
    }

    private static void runSocket(
            WorkerConfiguration configuration,
            WorkerDeliveryCodec codec,
            WorkerCommandProcessor processor
    ) throws InterruptedException {
        try (SocketWorkerTransport transport = new SocketWorkerTransport(
                configuration.serverUrl(),
                configuration.workerId(),
                configuration.requestTimeout(),
                configuration.reconnectInterval(),
                codec,
                processor
        )) {
            runLongLived(transport, transport::runForever);
        }
    }

    private static void runLongLived(
            AutoCloseable transport,
            InterruptibleRun run
    ) throws InterruptedException {
        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> {
                    try {
                        transport.close();
                    } catch (Exception ignored) {
                        // Process shutdown is best effort.
                    }
                }, "worker-shutdown")
        );
        run.run();
    }

    @FunctionalInterface
    private interface InterruptibleRun {

        void run() throws InterruptedException;
    }
}
