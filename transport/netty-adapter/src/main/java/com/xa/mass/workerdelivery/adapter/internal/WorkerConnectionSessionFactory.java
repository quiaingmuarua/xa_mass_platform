package com.xa.mass.workerdelivery.adapter.internal;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import io.netty.channel.ChannelHandlerContext;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;

final class WorkerConnectionSessionFactory {

    private final BoundWorkerConnectionDirectory connections;
    private final WorkerDeliveryCodec codec;
    private final BoundedDeliveryReportQueue reportQueue;
    private final WorkerDeliveryGatewayClient gateway;
    private final String endpointManagerId;
    private final Duration sendTimeLimit;
    private final BooleanSupplier acceptingConnections;
    private final TextFrameStrategy frameStrategy;
    private final String operationPrefix;

    WorkerConnectionSessionFactory(
            BoundWorkerConnectionDirectory connections,
            WorkerDeliveryCodec codec,
            BoundedDeliveryReportQueue reportQueue,
            WorkerDeliveryGatewayClient gateway,
            String endpointManagerId,
            Duration sendTimeLimit,
            BooleanSupplier acceptingConnections,
            TextFrameStrategy frameStrategy,
            String operationPrefix
    ) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.reportQueue = Objects.requireNonNull(reportQueue, "reportQueue");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.endpointManagerId = Objects.requireNonNull(
                endpointManagerId,
                "endpointManagerId"
        );
        this.sendTimeLimit = Objects.requireNonNull(
                sendTimeLimit,
                "sendTimeLimit"
        );
        this.acceptingConnections = Objects.requireNonNull(
                acceptingConnections,
                "acceptingConnections"
        );
        this.frameStrategy = Objects.requireNonNull(
                frameStrategy,
                "frameStrategy"
        );
        this.operationPrefix = Objects.requireNonNull(
                operationPrefix,
                "operationPrefix"
        );
    }

    WorkerConnectionSession create(ChannelHandlerContext context) {
        return new WorkerConnectionSession(
                connections,
                codec,
                reportQueue,
                gateway,
                endpointManagerId,
                sendTimeLimit,
                acceptingConnections,
                context,
                frameStrategy,
                operationPrefix
        );
    }
}
