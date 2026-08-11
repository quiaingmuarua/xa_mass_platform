package com.xa.mass.workerdelivery.adapter.websocket;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterState;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.adapter.internal.NettyWorkerDeliveryAdapterRuntime;
import java.time.Duration;

public final class WebSocketWorkerDeliveryAdapter
        implements WorkerDeliveryAdapter {

    private final NettyWorkerDeliveryAdapterRuntime runtime;

    public WebSocketWorkerDeliveryAdapter(
            String adapterId,
            WorkerDeliveryGatewayClient gateway,
            String listenHost,
            int listenPort,
            Duration commandLoopInterval,
            int commandConsumeLimit,
            int commandQueueCapacity,
            Duration reportSubmitInterval,
            int reportQueueCapacity,
            Duration sendTimeLimit,
            Duration shutdownTimeout
    ) {
        runtime = NettyWorkerDeliveryAdapterRuntime.webSocket(
                adapterId,
                gateway,
                listenHost,
                listenPort,
                commandLoopInterval,
                commandConsumeLimit,
                commandQueueCapacity,
                reportSubmitInterval,
                reportQueueCapacity,
                sendTimeLimit,
                shutdownTimeout
        );
    }

    @Override
    public String adapterId() {
        return runtime.adapterId();
    }

    @Override
    public WorkerDeliveryAdapterState state() {
        return runtime.state();
    }

    public String listenHost() {
        return runtime.listenHost();
    }

    public int listenPort() {
        return runtime.listenPort();
    }

    @Override
    public void start() {
        runtime.start();
    }

    @Override
    public void close() {
        runtime.close();
    }

    int activeConnectionCount() {
        return runtime.activeConnectionCount();
    }

    int trackedConnectionCount() {
        return runtime.trackedConnectionCount();
    }
}
