package com.xa.mass.workerdelivery.adapter.netty.internal.websocket;

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
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

final class WebSocketWorkerIdentityHandler
        extends SimpleChannelInboundHandler<WebSocketFrame> {

    private final WebSocketWorkerRouteDirectory routes;
    private final WorkerDeliveryCodec codec;
    private final BoundedDeliveryReportQueue reportQueue;
    private final WorkerDeliveryGatewayClient gateway;
    private final String endpointManagerId;
    private final Duration sendTimeLimit;
    private final BooleanSupplier acceptingConnections;
    private String verifyingWorkerId;

    WebSocketWorkerIdentityHandler(
            WebSocketWorkerRouteDirectory routes,
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
    public void userEventTriggered(ChannelHandlerContext context, Object event) {
        if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            if (!acceptingConnections.getAsBoolean()) {
                terminate(
                        context.channel(),
                        WebSocketCloseReason.ADAPTER_STOPPING
                );
            }
            return;
        }
        context.fireUserEventTriggered(event);
    }

    @Override
    protected void channelRead0(
            ChannelHandlerContext context,
            WebSocketFrame frame
    ) {
        if (verifyingWorkerId != null) {
            return;
        }
        if (frame instanceof BinaryWebSocketFrame) {
            terminate(
                    context.channel(),
                    WebSocketCloseReason.BINARY_UNSUPPORTED
            );
            return;
        }
        if (!(frame instanceof TextWebSocketFrame text)) {
            return;
        }

        DeliveryReport report = codec.decodeDeliveryReport(text.text());
        if (report == null) {
            terminate(context.channel(), WebSocketCloseReason.INVALID_REPORT);
            return;
        }
        if (report.dst() != ADAPTER
                || !WORKER_CONNECTION_IDENTIFY_EVENT_CODE.equals(
                report.messageType()
        )) {
            terminate(
                    context.channel(),
                    WebSocketCloseReason.IDENTITY_REQUIRED
            );
            return;
        }
        if (!isValidIdentity(report)) {
            terminate(
                    context.channel(),
                    WebSocketCloseReason.VERIFICATION_FAILED
            );
            return;
        }
        identifyAndBind(context, report.sourceId());
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        cancelCurrentVerification(context.channel());
        context.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        terminate(context.channel(), WebSocketCloseReason.TRANSPORT_ERROR);
    }

    private void identifyAndBind(
            ChannelHandlerContext context,
            String identifyingWorkerId
    ) {
        if (!acceptingConnections.getAsBoolean()) {
            terminate(
                    context.channel(),
                    WebSocketCloseReason.VERIFICATION_FAILED
            );
            return;
        }
        Channel channel = context.channel();
        if (routes.isRouteVerified(identifyingWorkerId)) {
            bindVerified(context, identifyingWorkerId);
            return;
        }
        if (!routes.beginVerification(identifyingWorkerId, channel)) {
            if (routes.isRouteVerified(identifyingWorkerId)) {
                bindVerified(context, identifyingWorkerId);
            } else {
                terminate(
                        channel,
                        WebSocketCloseReason.VERIFICATION_IN_PROGRESS
                );
            }
            return;
        }
        verifyingWorkerId = identifyingWorkerId;

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
        if (!channel.isActive()
                || !routes.isVerificationPending(
                identifyingWorkerId,
                channel
        )) {
            return;
        }
        if (failure != null) {
            routes.cancelVerification(identifyingWorkerId, channel);
            Throwable cause = unwrap(failure);
            if (cause instanceof WorkerDeliveryAdapterException classified
                    && classified.errorCode()
                    == WorkerDeliveryAdapterErrorCode.WORKER_ROUTE_REJECTED) {
                sendCloseCommand(channel);
            } else {
                terminate(
                        channel,
                        WebSocketCloseReason.VERIFICATION_FAILED
                );
            }
            return;
        }
        if (!acceptingConnections.getAsBoolean()) {
            routes.cancelVerification(identifyingWorkerId, channel);
            terminate(channel, WebSocketCloseReason.ADAPTER_STOPPING);
            return;
        }

        WebSocketBoundWorkerHandler boundHandler =
                new WebSocketBoundWorkerHandler(
                        routes,
                        codec,
                        reportQueue,
                        identifyingWorkerId
        );
        try {
            context.pipeline().replace(
                    this,
                    "websocket-bound-worker",
                    boundHandler
            );
            if (!channel.isActive()
                    || !routes.completeVerificationAndActivate(
                    identifyingWorkerId,
                    channel
            )) {
                terminate(
                        channel,
                        WebSocketCloseReason.VERIFICATION_FAILED
                );
                return;
            }
        } catch (RuntimeException error) {
            routes.cancelVerification(identifyingWorkerId, channel);
            routes.deactivate(identifyingWorkerId, channel);
            WebSocketCloseReason.TRANSPORT_ERROR.close(channel);
            return;
        }
        if (!acceptingConnections.getAsBoolean()) {
            routes.close(
                    identifyingWorkerId,
                    channel,
                    WebSocketCloseReason.ADAPTER_STOPPING
            );
        }
    }

    private void bindVerified(
            ChannelHandlerContext context,
            String identifyingWorkerId
    ) {
        Channel channel = context.channel();
        if (!channel.isActive() || !acceptingConnections.getAsBoolean()) {
            terminate(channel, WebSocketCloseReason.ADAPTER_STOPPING);
            return;
        }
        WebSocketBoundWorkerHandler boundHandler =
                new WebSocketBoundWorkerHandler(
                        routes,
                        codec,
                        reportQueue,
                        identifyingWorkerId
                );
        try {
            context.pipeline().replace(
                    this,
                    "websocket-bound-worker",
                    boundHandler
            );
            if (!routes.activateIfVerified(
                    identifyingWorkerId,
                    channel
            )) {
                terminate(
                        channel,
                        WebSocketCloseReason.VERIFICATION_FAILED
                );
            }
        } catch (RuntimeException error) {
            routes.deactivate(identifyingWorkerId, channel);
            WebSocketCloseReason.TRANSPORT_ERROR.close(channel);
        }
    }

    private void sendCloseCommand(Channel channel) {
        cancelCurrentVerification(channel);
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
            channel.writeAndFlush(new TextWebSocketFrame(
                    codec.encodeDeliveryCommand(command)
            )).addListener(ChannelFutureListener.CLOSE);
        } catch (RuntimeException error) {
            WebSocketCloseReason.VERIFICATION_FAILED.close(channel);
        }
    }

    private void terminate(Channel channel, WebSocketCloseReason reason) {
        cancelCurrentVerification(channel);
        reason.close(channel);
    }

    private void cancelCurrentVerification(Channel channel) {
        String workerId = verifyingWorkerId;
        if (workerId != null) {
            routes.cancelVerification(workerId, channel);
        }
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
}
