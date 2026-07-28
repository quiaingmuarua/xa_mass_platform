package com.xa.mass.workerdelivery.adapter.socket;

import static com.xa.mass.workerdelivery.adapter.message.WorkerMessageHandlingResult.ACCEPTED;

import com.xa.mass.workerdelivery.adapter.message.WorkerConnectionMessageDispatcher;
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
    private final WorkerConnectionMessageDispatcher dispatcher;
    private final BooleanSupplier acceptingConnections;
    private String workerId;
    private Channel workerChannel;

    SocketWorkerHandler(
            NettySocketWorkerConnectionRegistry connections,
            WorkerDeliveryCodec codec,
            WorkerConnectionMessageDispatcher dispatcher,
            BooleanSupplier acceptingConnections
    ) {
        this.connections = Objects.requireNonNull(
                connections,
                "connections"
        );
        this.codec = Objects.requireNonNull(codec, "codec");
        this.dispatcher = Objects.requireNonNull(
                dispatcher,
                "dispatcher"
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
            String line
    ) {
        var bind = codec.decodeWorkerConnectionBind(line);
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
            String line
    ) {
        if (codec.decodeWorkerConnectionBind(line) != null) {
            disconnect();
            context.close();
            return;
        }
        var message = codec.decodeWorkerConnectionMessage(line);
        if (message == null
                || dispatcher.dispatch(workerId, message) != ACCEPTED) {
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
