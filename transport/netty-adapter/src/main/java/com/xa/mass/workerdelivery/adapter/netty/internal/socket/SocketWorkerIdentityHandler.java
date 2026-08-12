package com.xa.mass.workerdelivery.adapter.netty.internal.socket;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_CLOSE_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_IDENTIFY_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.adapter.netty.internal.gateway.BoundedDeliveryReportQueue;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

final class SocketWorkerIdentityHandler
        extends SimpleChannelInboundHandler<String> {

    private final SocketBoundWorkerDirectory connections;
    private final WorkerDeliveryCodec codec;
    private final BoundedDeliveryReportQueue reportQueue;
    private final WorkerDeliveryGatewayClient gateway;
    private final String endpointManagerId;
    private final Duration sendTimeLimit;
    private final BooleanSupplier acceptingConnections;
    private IdentityPhase phase = IdentityPhase.AWAITING_IDENTITY;

    SocketWorkerIdentityHandler(
            SocketBoundWorkerDirectory connections,
            WorkerDeliveryCodec codec,
            BoundedDeliveryReportQueue reportQueue,
            WorkerDeliveryGatewayClient gateway,
            String endpointManagerId,
            Duration sendTimeLimit,
            BooleanSupplier acceptingConnections
    ) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.reportQueue = Objects.requireNonNull(reportQueue, "reportQueue");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.endpointManagerId = requireEndpointManagerId(endpointManagerId);
        this.sendTimeLimit = Objects.requireNonNull(
                sendTimeLimit,
                "sendTimeLimit"
        );
        this.acceptingConnections = Objects.requireNonNull(
                acceptingConnections,
                "acceptingConnections"
        );
    }

    @Override
    public void channelActive(ChannelHandlerContext context) {
        if (acceptingConnections.getAsBoolean()) {
            context.fireChannelActive();
        } else {
            terminate(context.channel());
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, String line) {
        if (phase == IdentityPhase.VERIFYING) {
            terminate(context.channel());
            return;
        }
        if (phase != IdentityPhase.AWAITING_IDENTITY) {
            return;
        }

        DeliveryReport report = codec.decodeDeliveryReport(line);
        if (report == null) {
            terminate(context.channel());
            return;
        }
        if (report.dst() != ADAPTER
                || !WORKER_CONNECTION_IDENTIFY_EVENT_CODE.equals(
                report.messageType()
        )) {
            terminate(context.channel());
            return;
        }
        if (!isValidIdentity(report)) {
            terminate(context.channel());
            return;
        }
        verifyAndBind(context, report.sourceId());
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        phase = IdentityPhase.TERMINATED;
        context.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        terminate(context.channel());
    }

    private void verifyAndBind(
            ChannelHandlerContext context,
            String identifyingWorkerId
    ) {
        if (!acceptingConnections.getAsBoolean()) {
            terminate(context.channel());
            return;
        }
        phase = IdentityPhase.VERIFYING;
        Channel channel = context.channel();
        channel.config().setAutoRead(false);

        CompletionStage<Void> verification;
        try {
            verification = Objects.requireNonNull(
                    gateway.verifyWorkerRoute(
                            endpointManagerId,
                            identifyingWorkerId
                    ),
                    "Worker route verification returned null"
            );
        } catch (RuntimeException error) {
            finishVerification(context, identifyingWorkerId, error);
            return;
        }
        verification.whenComplete((ignored, failure) ->
                context.executor().execute(() -> finishVerification(
                        context,
                        identifyingWorkerId,
                        failure
                ))
        );
    }

    private void finishVerification(
            ChannelHandlerContext context,
            String identifyingWorkerId,
            Throwable failure
    ) {
        Channel channel = context.channel();
        if (!channel.isActive() || phase != IdentityPhase.VERIFYING) {
            return;
        }
        if (failure != null) {
            Throwable cause = unwrap(failure);
            if (cause instanceof WorkerDeliveryAdapterException classified
                    && classified.errorCode()
                    == WorkerDeliveryAdapterErrorCode.WORKER_ROUTE_REJECTED) {
                sendCloseCommand(channel);
            } else {
                terminate(channel);
            }
            return;
        }
        if (!acceptingConnections.getAsBoolean()) {
            terminate(channel);
            return;
        }

        SocketBoundWorkerHandler boundHandler = new SocketBoundWorkerHandler(
                connections,
                codec,
                reportQueue,
                identifyingWorkerId
        );
        try {
            phase = IdentityPhase.TRANSFERRED;
            context.pipeline().replace(
                    this,
                    "socket-bound-worker",
                    boundHandler
            );
            if (!channel.isActive()) {
                return;
            }
            connections.activate(identifyingWorkerId, channel);
        } catch (RuntimeException error) {
            connections.deactivate(identifyingWorkerId, channel);
            phase = IdentityPhase.TERMINATED;
            SocketBoundWorkerDirectory.closeBestEffort(channel);
            return;
        }
        if (!acceptingConnections.getAsBoolean()) {
            connections.close(identifyingWorkerId, channel);
            return;
        }
        if (!channel.config().isAutoRead()) {
            channel.config().setAutoRead(true);
            channel.read();
        }
    }

    private void sendCloseCommand(Channel channel) {
        phase = IdentityPhase.TERMINATED;
        try {
            DeliveryCommand command = DeliveryCommand.create(
                    ADAPTER,
                    WORKER,
                    WORKER_CONNECTION_CLOSE_EVENT_CODE,
                    Math.addExact(
                            System.currentTimeMillis(),
                            sendTimeLimit.toMillis()
                    ),
                    "null",
                    ""
            );
            channel.writeAndFlush(
                    codec.encodeDeliveryCommand(command) + "\n"
            ).addListener(ChannelFutureListener.CLOSE);
        } catch (RuntimeException error) {
            SocketBoundWorkerDirectory.closeBestEffort(channel);
        }
    }

    private void terminate(Channel channel) {
        phase = IdentityPhase.TERMINATED;
        SocketBoundWorkerDirectory.closeBestEffort(channel);
    }

    private static boolean isValidIdentity(DeliveryReport report) {
        return report.src() == WORKER
                && report.dst() == ADAPTER
                && WORKER_CONNECTION_IDENTIFY_EVENT_CODE.equals(
                report.messageType()
        )
                && "200".equals(report.outcomeCode())
                && report.forward().isEmpty()
                && "null".equals(report.payload());
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String requireEndpointManagerId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "endpointManagerId must be non-blank"
            );
        }
        return value;
    }

    private enum IdentityPhase {
        AWAITING_IDENTITY,
        VERIFYING,
        TRANSFERRED,
        TERMINATED
    }
}
