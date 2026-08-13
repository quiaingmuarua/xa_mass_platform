package com.xa.mass.workerdelivery.adapter.netty;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SYSTEM_POLLING_ENDPOINT_MANAGER_ID;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionMechanism;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerRouteRegistry;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.NettyWorkerServer;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.SocketNettyWorkerServer;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.WebSocketNettyWorkerServer;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryCommandProcess;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryReportProcess;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import java.time.Duration;
import java.util.Objects;

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
        validate(
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
        return build(
                new WebSocketNettyWorkerServer(
                        adapterId,
                        listenHost,
                        listenPort,
                        sendTimeLimit,
                        shutdownTimeout
                ),
                adapterId,
                gateway,
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
        validate(
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
        return build(
                new SocketNettyWorkerServer(
                        adapterId,
                        listenHost,
                        listenPort,
                        sendTimeLimit,
                        shutdownTimeout
                ),
                adapterId,
                gateway,
                commandLoopInterval,
                commandConsumeLimit,
                commandQueueCapacity,
                reportSubmitInterval,
                reportQueueCapacity,
                sendTimeLimit,
                shutdownTimeout
        );
    }

    private static WorkerDeliveryAdapter build(
            NettyWorkerServer networkServer,
            String adapterId,
            WorkerDeliveryGatewayClient gateway,
            Duration commandLoopInterval,
            int commandConsumeLimit,
            int commandQueueCapacity,
            Duration reportSubmitInterval,
            int reportQueueCapacity,
            Duration sendTimeLimit,
            Duration shutdownTimeout
    ) {
        WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
        DeliveryReportProcess reportProcess = new DeliveryReportProcess(
                gateway.resultIngress(),
                adapterId,
                reportQueueCapacity
        );
        WorkerRouteRegistry routes = new WorkerRouteRegistry();
        WorkerConnectionMechanism connectionMechanism =
                new WorkerConnectionMechanism(
                        routes,
                        networkServer,
                        gateway.routeVerifier(),
                        codec,
                        reportProcess.acceptor(),
                        adapterId,
                        sendTimeLimit
                );
        DeliveryCommandProcess commandProcess = new DeliveryCommandProcess(
                gateway.commandSource(),
                connectionMechanism,
                reportProcess.acceptor(),
                codec,
                adapterId,
                commandConsumeLimit,
                commandQueueCapacity
        );
        return new NettyWorkerDeliveryAdapter(
                adapterId,
                commandLoopInterval,
                reportSubmitInterval,
                shutdownTimeout,
                networkServer,
                connectionMechanism,
                commandProcess,
                reportProcess
        );
    }

    private static void validate(
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
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must be non-blank");
        }
        if (SYSTEM_POLLING_ENDPOINT_MANAGER_ID.equals(adapterId)) {
            throw new IllegalArgumentException(
                    "system-polling cannot own an active Adapter"
            );
        }
        Objects.requireNonNull(gateway, "gateway");
        if (listenHost == null || listenHost.isBlank()) {
            throw new IllegalArgumentException(
                    "listenHost must be non-blank"
            );
        }
        if (listenPort < 1 || listenPort > 65_535) {
            throw new IllegalArgumentException(
                    "listenPort must be between 1 and 65535"
            );
        }
        requirePositive(commandLoopInterval, "commandLoopInterval");
        requirePositive(reportSubmitInterval, "resultSubmitInterval");
        requirePositive(sendTimeLimit, "sendTimeLimit");
        requirePositive(shutdownTimeout, "shutdownTimeout");
        if (commandConsumeLimit <= 0 || reportQueueCapacity <= 0) {
            throw new IllegalArgumentException(
                    "Adapter bounds must be positive"
            );
        }
        if (commandQueueCapacity < commandConsumeLimit) {
            throw new IllegalArgumentException(
                    "commandQueueCapacity must be at least commandConsumeLimit"
            );
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null
                || value.isZero()
                || value.isNegative()
                || value.toMillis() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
