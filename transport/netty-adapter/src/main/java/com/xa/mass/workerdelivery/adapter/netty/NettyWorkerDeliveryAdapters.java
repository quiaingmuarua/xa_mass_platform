package com.xa.mass.workerdelivery.adapter.netty;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import java.time.Duration;

/** Finite construction boundary for the built-in Netty Adapter types. */
public final class NettyWorkerDeliveryAdapters {

    private NettyWorkerDeliveryAdapters() {
    }

    public static WorkerDeliveryAdapter webSocket(
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
        return new WebSocketWorkerDeliveryAdapter(
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

    public static WorkerDeliveryAdapter socket(
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
        return new SocketWorkerDeliveryAdapter(
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
}
