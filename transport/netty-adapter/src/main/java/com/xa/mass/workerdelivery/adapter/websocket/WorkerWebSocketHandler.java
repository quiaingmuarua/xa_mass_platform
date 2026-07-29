package com.xa.mass.workerdelivery.adapter.websocket;

import static com.xa.mass.workerdelivery.adapter.message.WorkerResultHandlingResult.ACCEPTED;
import static com.xa.mass.workerdelivery.adapter.message.WorkerResultHandlingResult.ADAPTER_CLOSED;
import static com.xa.mass.workerdelivery.adapter.message.WorkerResultHandlingResult.BUFFER_FULL;
import static com.xa.mass.workerdelivery.adapter.websocket.WorkerConnectionRegistry.ConnectionCloseReason.ADAPTER_STOPPING;
import static com.xa.mass.workerdelivery.adapter.websocket.WorkerConnectionRegistry.ConnectionCloseReason.RESULT_BUFFER_FULL;
import static com.xa.mass.workerdelivery.adapter.websocket.WorkerConnectionRegistry.ConnectionCloseReason.TRANSPORT_ERROR;

import com.xa.mass.workerdelivery.adapter.message.WorkerResultPayloadHandler;
import com.xa.mass.workerdelivery.adapter.message.WorkerResultHandlingResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import java.util.Objects;
import java.util.function.BooleanSupplier;

final class WorkerWebSocketHandler
        extends SimpleChannelInboundHandler<WebSocketFrame> {

    static final String WORKER_PATH =
            "/api/v1/worker-delivery/websocket";

    private final WorkerConnectionRegistry connections;
    private final WorkerDeliveryCodec codec;
    private final WorkerResultPayloadHandler resultHandler;
    private final BooleanSupplier acceptingConnections;
    private boolean handshakeComplete;
    private String workerId;
    private Channel workerChannel;

    WorkerWebSocketHandler(
            WorkerConnectionRegistry connections,
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
    public void userEventTriggered(
            ChannelHandlerContext context,
            Object event
    ) {
        if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete
                ignored) {
            if (!acceptingConnections.getAsBoolean()) {
                close(context, 1001, "Adapter is stopping");
                return;
            }
            handshakeComplete = true;
            return;
        }
        context.fireUserEventTriggered(event);
    }

    @Override
    protected void channelRead0(
            ChannelHandlerContext context,
            WebSocketFrame frame
    ) {
        if (!handshakeComplete) {
            close(context, 1008, "Worker handshake is incomplete");
            return;
        }
        if (frame instanceof TextWebSocketFrame text) {
            if (workerChannel == null) {
                handleBind(context, text.text());
            } else {
                handleResult(context, text.text());
            }
            return;
        }
        if (frame instanceof BinaryWebSocketFrame) {
            disconnect();
            close(context, 1003, "Binary frames are unsupported");
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
            connections.close(
                    currentWorkerId,
                    current,
                    TRANSPORT_ERROR
            );
        } else {
            context.close();
        }
    }

    private void handleResult(
            ChannelHandlerContext context,
            String encodedResult
    ) {
        WorkerResultHandlingResult acceptance =
                resultHandler.handle(workerId, encodedResult);
        if (acceptance == ACCEPTED) {
            return;
        }
        Channel current = workerChannel;
        String currentWorkerId = workerId;
        clearConnection();
        if (current != null && currentWorkerId != null
                && acceptance == BUFFER_FULL) {
            connections.close(
                    currentWorkerId,
                    current,
                    RESULT_BUFFER_FULL
            );
        } else if (current != null && currentWorkerId != null
                && acceptance == ADAPTER_CLOSED) {
            connections.close(
                    currentWorkerId,
                    current,
                    ADAPTER_STOPPING
            );
        } else {
            if (current != null && currentWorkerId != null) {
                connections.unbind(currentWorkerId, current);
            }
            close(context, 1007, "Invalid Worker result");
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

    private void handleBind(
            ChannelHandlerContext context,
            String encodedBind
    ) {
        var bind = codec.decodeWorkerConnectionBind(encodedBind);
        if (bind == null) {
            close(context, 1008, "Worker binding is required");
            return;
        }
        if (!acceptingConnections.getAsBoolean()) {
            close(context, 1001, "Adapter is stopping");
            return;
        }
        Channel opened = context.channel();
        workerId = bind.workerId();
        workerChannel = opened;
        connections.bind(workerId, opened);
        if (!acceptingConnections.getAsBoolean()) {
            String boundWorkerId = workerId;
            clearConnection();
            connections.close(
                    boundWorkerId,
                    opened,
                    ADAPTER_STOPPING
            );
        }
    }

    private void clearConnection() {
        workerChannel = null;
        workerId = null;
    }

    private static void close(
            ChannelHandlerContext context,
            int code,
            String reason
    ) {
        context.writeAndFlush(new CloseWebSocketFrame(code, reason))
                .addListener(ignored -> context.close());
    }
}
