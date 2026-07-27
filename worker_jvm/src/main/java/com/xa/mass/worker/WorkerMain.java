package com.xa.mass.worker;

import com.xa.mass.worker.execution.PhoneInspectHandler;
import com.xa.mass.worker.execution.WorkerCommandProcessor;
import com.xa.mass.worker.transport.polling.PollingWorkerTransport;
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
                        new PhoneInspectHandler()
                )
        );
        if (configuration.transport() == WorkerTransportMode.POLLING) {
            new PollingWorkerTransport(
                    configuration.serverUrl(),
                    configuration.endpointManagerId(),
                    configuration.workerId(),
                    configuration.requestTimeout(),
                    codec,
                    processor
            ).runForever(configuration.pollInterval());
            return;
        }

        try (WebSocketWorkerTransport transport =
                     new WebSocketWorkerTransport(
                             configuration.serverUrl(),
                             configuration.workerId(),
                             configuration.requestTimeout(),
                             configuration.reconnectInterval(),
                             codec,
                             processor
                     )) {
            Runtime.getRuntime().addShutdownHook(
                    new Thread(transport::close, "worker-shutdown")
            );
            transport.runForever();
        }
    }
}
