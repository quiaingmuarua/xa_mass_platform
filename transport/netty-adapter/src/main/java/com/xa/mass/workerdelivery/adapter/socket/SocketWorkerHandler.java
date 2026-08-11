package com.xa.mass.workerdelivery.adapter.socket;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_CLOSE_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_IDENTIFY_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResultOutcomeClass.SUCCESS;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResultOutcomeClass.WORKER_FAILURE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.classifyWorkerResultOutcomeCode;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.adapter.message.AdapterWorkerEventDispatcher;
import com.xa.mass.workerdelivery.adapter.result.BoundedWorkerResultQueue;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

final class SocketWorkerHandler extends SimpleChannelInboundHandler<String> {

    private static final System.Logger LOGGER = System.getLogger(
            SocketWorkerHandler.class.getName()
    );

    private final NettySocketWorkerConnectionRegistry connections;
    private final WorkerDeliveryCodec codec;
    private final BoundedWorkerResultQueue resultQueue;
    private final WorkerDeliveryGatewayClient gateway;
    private final String endpointManagerId;
    private final Duration sendTimeLimit;
    private final BooleanSupplier acceptingConnections;
    private AdapterWorkerEventDispatcher adapterEvents;
    private BindingPhase bindingPhase = BindingPhase.UNBOUND;
    private String workerId;

    SocketWorkerHandler(
            NettySocketWorkerConnectionRegistry connections,
            WorkerDeliveryCodec codec,
            BoundedWorkerResultQueue resultQueue,
            WorkerDeliveryGatewayClient gateway,
            String endpointManagerId,
            Duration sendTimeLimit,
            BooleanSupplier acceptingConnections
    ) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.resultQueue = Objects.requireNonNull(resultQueue, "resultQueue");
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

    @Override
    public void handlerAdded(ChannelHandlerContext context) {
        adapterEvents = new AdapterWorkerEventDispatcher(
                sendTimeLimit,
                id -> verifyAndActivate(context, id)
        );
    }

    @Override
    public void channelActive(ChannelHandlerContext context) {
        if (acceptingConnections.getAsBoolean()) {
            context.fireChannelActive();
        } else {
            context.close();
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, String line) {
        WorkerResult result = codec.decodeWorkerResult(line);
        if (result == null) {
            if (bindingPhase == BindingPhase.BOUND) {
                logDrop("socket.dropMalformedWorkerResult", null);
            } else {
                context.close();
            }
            return;
        }
        switch (bindingPhase) {
            case UNBOUND -> handleUnbound(context, result);
            case VERIFYING -> context.close();
            case BOUND -> handleBound(context, result, line);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        disconnect(context.channel());
        context.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        if (bindingPhase == BindingPhase.BOUND && workerId != null) {
            connections.close(workerId, context.channel());
        } else {
            context.close();
        }
    }

    private void handleUnbound(
            ChannelHandlerContext context,
            WorkerResult result
    ) {
        if (result.dst() == ADAPTER
                && WORKER_CONNECTION_IDENTIFY_EVENT_CODE.equals(
                result.messageType()
        )) {
            dispatchAdapterEvent(context, result);
        } else {
            context.close();
        }
    }

    private void handleBound(
            ChannelHandlerContext context,
            WorkerResult result,
            String encodedResult
    ) {
        if (result.dst() == ADAPTER) {
            if (WORKER_CONNECTION_IDENTIFY_EVENT_CODE.equals(
                    result.messageType()
            )) {
                logDrop("socket.dropRepeatedIdentity", result);
            } else {
                dispatchAdapterEvent(context, result);
            }
        } else if (result.dst() == TASK) {
            acceptTaskResult(context, result, encodedResult);
        } else {
            logDrop("socket.dropUnsupportedDestination", result);
        }
    }

    private void dispatchAdapterEvent(
            ChannelHandlerContext context,
            WorkerResult result
    ) {
        adapterEvents.dispatch(result).whenComplete((response, failure) -> {
            if (!context.channel().isActive()) {
                return;
            }
            if (failure != null) {
                if (bindingPhase == BindingPhase.BOUND) {
                    logDrop("socket.dropAdapterEventFailure", result);
                } else {
                    context.close();
                }
            } else if (response.isPresent()) {
                sendAdapterCommand(context, response.orElseThrow());
            } else {
                resumeAfterIdentification(context);
            }
        });
    }

    private CompletionStage<Void> verifyAndActivate(
            ChannelHandlerContext context,
            String identifyingWorkerId
    ) {
        if (bindingPhase != BindingPhase.UNBOUND
                || !acceptingConnections.getAsBoolean()) {
            return CompletableFuture.failedFuture(verificationFailure(
                    WorkerDeliveryAdapterErrorCode.WORKER_MESSAGE_INVALID,
                    "Worker cannot be identified now"
            ));
        }
        bindingPhase = BindingPhase.VERIFYING;
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
            return CompletableFuture.failedFuture(error);
        }
        return verification.handleAsync((ignored, failure) -> {
            if (failure != null) {
                throw new CompletionException(failure);
            }
            if (bindingPhase != BindingPhase.VERIFYING
                    || !channel.isActive()
                    || !acceptingConnections.getAsBoolean()) {
                throw verificationFailure(
                        WorkerDeliveryAdapterErrorCode.GATEWAY_UNAVAILABLE,
                        "Worker could not be activated"
                );
            }
            connections.activate(identifyingWorkerId, channel);
            workerId = identifyingWorkerId;
            bindingPhase = BindingPhase.BOUND;
            if (!acceptingConnections.getAsBoolean()) {
                connections.close(workerId, channel);
                throw verificationFailure(
                        WorkerDeliveryAdapterErrorCode.GATEWAY_UNAVAILABLE,
                        "Adapter stopped while activating Worker"
                );
            }
            return null;
        }, context.executor());
    }

    private void acceptTaskResult(
            ChannelHandlerContext context,
            WorkerResult result,
            String encodedResult
    ) {
        var outcome = classifyWorkerResultOutcomeCode(result.outcomeCode());
        if (outcome != SUCCESS && outcome != WORKER_FAILURE) {
            logDrop("socket.dropWorkerOutcome", result);
            return;
        }
        if (resultQueue.offer(encodedResult)
                != BoundedWorkerResultQueue.OfferStatus.ACCEPTED) {
            connections.close(workerId, context.channel());
        }
    }

    private void resumeAfterIdentification(ChannelHandlerContext context) {
        Channel channel = context.channel();
        if (bindingPhase == BindingPhase.BOUND
                && !channel.config().isAutoRead()) {
            channel.config().setAutoRead(true);
            context.read();
        }
    }

    private void sendAdapterCommand(
            ChannelHandlerContext context,
            WorkerCommand command
    ) {
        if (!context.channel().isActive()) {
            return;
        }
        var send = context.writeAndFlush(
                codec.encodeWorkerCommand(command) + "\n"
        );
        if (WORKER_CONNECTION_CLOSE_EVENT_CODE.equals(command.messageType())) {
            send.addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void disconnect(Channel channel) {
        if (bindingPhase == BindingPhase.BOUND && workerId != null) {
            connections.deactivate(workerId, channel);
        }
        bindingPhase = BindingPhase.UNBOUND;
        workerId = null;
    }

    private void logDrop(String operation, WorkerResult result) {
        LOGGER.log(
                System.Logger.Level.WARNING,
                "errorCode={0} operation={1} phase={2} messageType={3}",
                WorkerDeliveryAdapterErrorCode.WORKER_MESSAGE_INVALID.code(),
                operation,
                bindingPhase,
                result == null ? "<malformed>" : result.messageType()
        );
    }

    private static WorkerDeliveryAdapterException verificationFailure(
            WorkerDeliveryAdapterErrorCode errorCode,
            String message
    ) {
        return new WorkerDeliveryAdapterException(
                errorCode,
                "socket.identifyWorker",
                message,
                null
        );
    }

    private enum BindingPhase {
        UNBOUND,
        VERIFYING,
        BOUND
    }
}
