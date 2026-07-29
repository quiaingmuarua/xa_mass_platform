package com.xa.mass.workerdelivery.adapter.socket;

import static com.xa.mass.workerdelivery.adapter.message.WorkerResultHandlingResult.ACCEPTED;

import com.xa.mass.workerdelivery.adapter.message.WorkerResultPayloadHandler;
import com.xa.mass.workerdelivery.adapter.message.WorkerResultHandlingResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
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
    private final BooleanSupplier acceptingConnections;
    private String workerId;
    private Channel workerChannel;

    SocketWorkerHandler(
            NettySocketWorkerConnectionRegistry connections,
            WorkerDeliveryCodec codec,
            WorkerResultPayloadHandler resultHandler,
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
            handleBind(context, line);
        } else {
            handleResult(context, line);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        disconnect();
        context.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(
            ChannelHandlerContext context,
            Throwable cause
    ) {
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
        var bind = codec.decodeWorkerConnectionBind(encodedBind);
        if (bind == null || !acceptingConnections.getAsBoolean()) {
            context.close();
            return;
        }
        Channel opened = context.channel();
        workerId = bind.workerId();
        workerChannel = opened;
        connections.bind(workerId, opened);
        if (!acceptingConnections.getAsBoolean()) {
            String boundWorkerId = workerId;
            clearConnection();
            connections.close(boundWorkerId, opened);
        }
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
            connections.unbind(currentWorkerId, current);
        }
    }

    private void clearConnection() {
        workerChannel = null;
        workerId = null;
    }
}
