package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.adapter.netty.internal.gateway.BoundedDeliveryReportQueue;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import io.netty.channel.ChannelHandler;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Stateless construction boundary for per-Channel mechanism handlers. */
public final class WorkerConnectionHandlerFactory {

    private final WorkerRouteDirectory routes;
    private final WorkerDeliveryCodec codec;
    private final BoundedDeliveryReportQueue reportQueue;
    private final WorkerDeliveryGatewayClient gateway;
    private final String endpointManagerId;
    private final Duration sendTimeLimit;
    private final BooleanSupplier acceptingConnections;

    public WorkerConnectionHandlerFactory(
            WorkerRouteDirectory routes,
            WorkerDeliveryCodec codec,
            BoundedDeliveryReportQueue reportQueue,
            WorkerDeliveryGatewayClient gateway,
            String endpointManagerId,
            Duration sendTimeLimit,
            BooleanSupplier acceptingConnections
    ) {
        this.routes = Objects.requireNonNull(routes, "routes");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.reportQueue = Objects.requireNonNull(reportQueue, "reportQueue");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        if (endpointManagerId == null || endpointManagerId.isBlank()) {
            throw new IllegalArgumentException(
                    "endpointManagerId must be non-blank"
            );
        }
        this.endpointManagerId = endpointManagerId;
        this.sendTimeLimit = Objects.requireNonNull(
                sendTimeLimit,
                "sendTimeLimit"
        );
        this.acceptingConnections = Objects.requireNonNull(
                acceptingConnections,
                "acceptingConnections"
        );
    }

    public ChannelHandler newIdentityHandler() {
        return new WorkerIdentityHandler(this);
    }

    WorkerRouteDirectory routes() {
        return routes;
    }

    WorkerDeliveryCodec codec() {
        return codec;
    }

    BoundedDeliveryReportQueue reportQueue() {
        return reportQueue;
    }

    WorkerDeliveryGatewayClient gateway() {
        return gateway;
    }

    String endpointManagerId() {
        return endpointManagerId;
    }

    Duration sendTimeLimit() {
        return sendTimeLimit;
    }

    BooleanSupplier acceptingConnections() {
        return acceptingConnections;
    }

    ChannelHandler newBoundHandler(String workerId) {
        return new BoundWorkerHandler(
                routes,
                codec,
                reportQueue,
                workerId
        );
    }
}
