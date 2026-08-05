package com.xa.mass.workerdelivery.adapter.socket;

import static com.xa.mass.workerdelivery.adapter.message.WorkerResultHandlingResult.ACCEPTED;

import com.xa.mass.workerdelivery.adapter.message.WorkerResultPayloadHandler;
import com.xa.mass.workerdelivery.adapter.message.WorkerResultHandlingResult;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionBind;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.Objects;
import java.util.function.BooleanSupplier;

final class SocketWorkerHandler
        extends SimpleChannelInboundHandler<String> {

    private final NettySocketWorkerConnectionRegistry connections;
    private final WorkerDeliveryCodec codec;
    private final WorkerResultPayloadHandler resultHandler;
    private final WorkerDeliveryGatewayClient gateway;
    private final String endpointManagerId;
    private final BooleanSupplier acceptingConnections;
    private String workerId;
    private Channel workerChannel;
    private boolean verifying;
    private String deferredResult;

    SocketWorkerHandler(
            NettySocketWorkerConnectionRegistry connections,
            WorkerDeliveryCodec codec,
            WorkerResultPayloadHandler resultHandler,
            WorkerDeliveryGatewayClient gateway,
            String endpointManagerId,
            BooleanSupplier acceptingConnections
    ) {
        this.connections = Objects.requireNonNull(
                connections,
                "connections"
        );
        this.codec = Objects.requireNonNull(codec, "codec");
        this.resultHandler = Objects.requireNonNull(
                resultHandler,
                "resultHandler"
        );
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        if (endpointManagerId == null || endpointManagerId.isBlank()) {
            throw new IllegalArgumentException(
                    "endpointManagerId must be non-blank"
            );
        }
        this.endpointManagerId = endpointManagerId;
        this.acceptingConnections = Objects.requireNonNull(
                acceptingConnections,
                "acceptingConnections"
        );
    }

    @Override
    public void channelActive(ChannelHandlerContext context) {
        if (!acceptingConnections.getAsBoolean()) {
            context.close();
            return;
        }
        context.fireChannelActive();
    }

    @Override
    protected void channelRead0(
            ChannelHandlerContext context,
            String line
    ) {
        if (workerChannel == null) {
            if (verifying) {
                deferResult(context, line);
            } else {
                handleBind(context, line);
            }
        } else {
            handleResult(context, line);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        verifying = false;
        deferredResult = null;
        disconnect();
        context.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(
            ChannelHandlerContext context,
            Throwable cause
    ) {
        verifying = false;
        deferredResult = null;
        Channel current = workerChannel;
        String currentWorkerId = workerId;
        clearConnection();
        if (current != null && currentWorkerId != null) {
            connections.close(currentWorkerId, current);
        } else {
            context.close();
        }
    }

    private void handleBind(
            ChannelHandlerContext context,
            String encodedBind
    ) {
        if (verifying || !acceptingConnections.getAsBoolean()) {
            context.close();
            return;
        }
        WorkerConnectionBind bind;
        try {
            bind = codec.decodeWorkerConnectionBind(encodedBind);
        } catch (IllegalArgumentException error) {
            context.close();
            return;
        }
        verifying = true;
        Channel opened = context.channel();
        opened.config().setAutoRead(false);
        gateway.verifyWorkerRoute(
                endpointManagerId,
                bind.workerId()
        ).whenComplete(
                (ignored, error) -> context.executor().execute(() -> {
                    if (!verifying || !opened.isActive()) {
                        return;
                    }
                    verifying = false;
                    if (error != null
                            || !activate(context, bind.workerId())) {
                        deferredResult = null;
                        context.close();
                        return;
                    }
                    String deferred = deferredResult;
                    deferredResult = null;
                    if (deferred != null) {
                        handleResult(context, deferred);
                    }
                    if (workerChannel != opened) {
                        return;
                    }
                    opened.config().setAutoRead(true);
                    context.read();
                })
        );
    }

    private void deferResult(
            ChannelHandlerContext context,
            String encodedResult
    ) {
        if (deferredResult != null) {
            deferredResult = null;
            context.close();
            return;
        }
        deferredResult = encodedResult;
    }

    private boolean activate(
            ChannelHandlerContext context,
            String boundWorkerId
    ) {
        if (!acceptingConnections.getAsBoolean()
                || !context.channel().isActive()) {
            return false;
        }
        Channel opened = context.channel();
        workerId = boundWorkerId;
        workerChannel = opened;
        connections.activate(workerId, opened);
        if (!acceptingConnections.getAsBoolean()) {
            String stoppingWorkerId = workerId;
            clearConnection();
            connections.close(stoppingWorkerId, opened);
            return false;
        }
        return true;
    }

    private void handleResult(
            ChannelHandlerContext context,
            String encodedResult
    ) {
        WorkerResultHandlingResult result =
                resultHandler.handle(workerId, encodedResult);
        if (result != ACCEPTED) {
            disconnect();
            context.close();
        }
    }

    private void disconnect() {
        Channel current = workerChannel;
        String currentWorkerId = workerId;
        clearConnection();
        if (current != null && currentWorkerId != null) {
            connections.deactivate(currentWorkerId, current);
        }
    }

    private void clearConnection() {
        workerChannel = null;
        workerId = null;
    }
}
