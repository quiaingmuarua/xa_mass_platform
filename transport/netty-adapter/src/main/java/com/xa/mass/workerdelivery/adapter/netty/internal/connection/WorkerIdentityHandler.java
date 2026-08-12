package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_CLOSE_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_IDENTIFY_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.AdapterConnectionCloseReason;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

final class WorkerIdentityHandler extends SimpleChannelInboundHandler<String> {

    private final WorkerConnectionHandlerFactory handlers;
    private String verifyingWorkerId;

    WorkerIdentityHandler(WorkerConnectionHandlerFactory handlers) {
        this.handlers = Objects.requireNonNull(handlers, "handlers");
    }

    @Override
    protected void channelRead0(
            ChannelHandlerContext context,
            String encodedReport
    ) {
        if (verifyingWorkerId != null) {
            return;
        }
        DeliveryReport report = handlers.codec().decodeDeliveryReport(
                encodedReport
        );
        if (report == null) {
            terminate(
                    context.channel(),
                    AdapterConnectionCloseReason.INVALID_REPORT
            );
            return;
        }
        if (report.dst() != ADAPTER
                || !WORKER_CONNECTION_IDENTIFY_EVENT_CODE.equals(
                report.messageType()
        )) {
            terminate(
                    context.channel(),
                    AdapterConnectionCloseReason.IDENTITY_REQUIRED
            );
            return;
        }
        if (!isValidIdentity(report)) {
            terminate(
                    context.channel(),
                    AdapterConnectionCloseReason.VERIFICATION_FAILED
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
        terminate(
                context.channel(),
                AdapterConnectionCloseReason.TRANSPORT_ERROR
        );
    }

    private void identifyAndBind(
            ChannelHandlerContext context,
            String identifyingWorkerId
    ) {
        if (!handlers.acceptingConnections().getAsBoolean()) {
            terminate(
                    context.channel(),
                    AdapterConnectionCloseReason.VERIFICATION_FAILED
            );
            return;
        }
        Channel channel = context.channel();
        if (handlers.routes().isRouteVerified(identifyingWorkerId)) {
            bindVerified(context, identifyingWorkerId);
            return;
        }
        if (!handlers.routes().beginVerification(
                identifyingWorkerId,
                channel
        )) {
            if (handlers.routes().isRouteVerified(identifyingWorkerId)) {
                bindVerified(context, identifyingWorkerId);
            } else {
                terminate(
                        channel,
                        AdapterConnectionCloseReason.VERIFICATION_IN_PROGRESS
                );
            }
            return;
        }
        verifyingWorkerId = identifyingWorkerId;

        CompletionStage<Void> verification;
        try {
            verification = Objects.requireNonNull(
                    handlers.gateway().verifyWorkerRoute(
                            handlers.endpointManagerId(),
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
                || !handlers.routes().isVerificationPending(
                identifyingWorkerId,
                channel
        )) {
            return;
        }
        if (failure != null) {
            handlers.routes().cancelVerification(
                    identifyingWorkerId,
                    channel
            );
            Throwable cause = unwrap(failure);
            if (cause instanceof WorkerDeliveryAdapterException classified
                    && classified.errorCode()
                    == WorkerDeliveryAdapterErrorCode.WORKER_ROUTE_REJECTED) {
                sendCloseCommand(channel);
            } else {
                terminate(
                        channel,
                        AdapterConnectionCloseReason.VERIFICATION_FAILED
                );
            }
            return;
        }
        if (!handlers.acceptingConnections().getAsBoolean()) {
            handlers.routes().cancelVerification(
                    identifyingWorkerId,
                    channel
            );
            terminate(
                    channel,
                    AdapterConnectionCloseReason.ADAPTER_STOPPING
            );
            return;
        }

        try {
            context.pipeline().replace(
                    this,
                    "bound-worker",
                    handlers.newBoundHandler(identifyingWorkerId)
            );
            if (!channel.isActive()
                    || !handlers.routes().completeVerificationAndActivate(
                    identifyingWorkerId,
                    channel
            )) {
                terminate(
                        channel,
                        AdapterConnectionCloseReason.VERIFICATION_FAILED
                );
                return;
            }
        } catch (RuntimeException error) {
            handlers.routes().cancelVerification(
                    identifyingWorkerId,
                    channel
            );
            handlers.routes().deactivate(identifyingWorkerId, channel);
            handlers.routes().closeUnbound(
                    channel,
                    AdapterConnectionCloseReason.TRANSPORT_ERROR
            );
            return;
        }
        if (!handlers.acceptingConnections().getAsBoolean()) {
            handlers.routes().close(
                    identifyingWorkerId,
                    channel,
                    AdapterConnectionCloseReason.ADAPTER_STOPPING
            );
        }
    }

    private void bindVerified(
            ChannelHandlerContext context,
            String identifyingWorkerId
    ) {
        Channel channel = context.channel();
        if (!channel.isActive()
                || !handlers.acceptingConnections().getAsBoolean()) {
            terminate(
                    channel,
                    AdapterConnectionCloseReason.ADAPTER_STOPPING
            );
            return;
        }
        try {
            context.pipeline().replace(
                    this,
                    "bound-worker",
                    handlers.newBoundHandler(identifyingWorkerId)
            );
            if (!handlers.routes().activateIfVerified(
                    identifyingWorkerId,
                    channel
            )) {
                terminate(
                        channel,
                        AdapterConnectionCloseReason.VERIFICATION_FAILED
                );
            }
        } catch (RuntimeException error) {
            handlers.routes().deactivate(identifyingWorkerId, channel);
            handlers.routes().closeUnbound(
                    channel,
                    AdapterConnectionCloseReason.TRANSPORT_ERROR
            );
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
                            handlers.sendTimeLimit().toMillis()
                    ),
                    "null",
                    ""
            );
            channel.writeAndFlush(
                    handlers.codec().encodeDeliveryCommand(command)
            ).addListener(ignored -> handlers.routes().closeUnbound(
                    channel,
                    AdapterConnectionCloseReason.VERIFICATION_FAILED
            ));
        } catch (RuntimeException error) {
            handlers.routes().closeUnbound(
                    channel,
                    AdapterConnectionCloseReason.VERIFICATION_FAILED
            );
        }
    }

    private void terminate(
            Channel channel,
            AdapterConnectionCloseReason reason
    ) {
        cancelCurrentVerification(channel);
        handlers.routes().closeUnbound(channel, reason);
    }

    private void cancelCurrentVerification(Channel channel) {
        String workerId = verifyingWorkerId;
        if (workerId != null) {
            handlers.routes().cancelVerification(workerId, channel);
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
}
