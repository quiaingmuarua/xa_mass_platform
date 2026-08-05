package com.xa.mass.workerdelivery.adapter.websocket;

import static com.xa.mass.workerdelivery.adapter.message.WorkerResultHandlingResult.ACCEPTED;
import static com.xa.mass.workerdelivery.adapter.message.WorkerResultHandlingResult.ADAPTER_CLOSED;
import static com.xa.mass.workerdelivery.adapter.message.WorkerResultHandlingResult.BUFFER_FULL;
import static com.xa.mass.workerdelivery.adapter.websocket.WorkerConnectionRegistry.ConnectionCloseReason.ADAPTER_STOPPING;
import static com.xa.mass.workerdelivery.adapter.websocket.WorkerConnectionRegistry.ConnectionCloseReason.RESULT_BUFFER_FULL;
import static com.xa.mass.workerdelivery.adapter.websocket.WorkerConnectionRegistry.ConnectionCloseReason.TRANSPORT_ERROR;

import com.xa.mass.workerdelivery.adapter.message.WorkerResultPayloadHandler;
import com.xa.mass.workerdelivery.adapter.message.WorkerResultHandlingResult;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionBind;
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
    private final WorkerDeliveryGatewayClient gateway;
    private final String endpointManagerId;
    private final BooleanSupplier acceptingConnections;
    private boolean handshakeComplete;
    private String workerId;
    private Channel workerChannel;
    private boolean verifying;
    private String deferredResult;

    WorkerWebSocketHandler(
            WorkerConnectionRegistry connections,
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
                if (verifying) {
                    deferResult(context, text.text());
                } else {
                    handleBind(context, text.text());
                }
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
                connections.deactivate(currentWorkerId, current);
            }
            close(context, 1007, "Invalid Worker result");
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

    private void handleBind(
            ChannelHandlerContext context,
            String encodedBind
    ) {
        if (verifying) {
            close(context, 1008, "Worker route verification is in progress");
            return;
        }
        WorkerConnectionBind bind;
        try {
            bind = codec.decodeWorkerConnectionBind(encodedBind);
        } catch (IllegalArgumentException error) {
            close(context, 1008, "Worker Bind is invalid");
            return;
        }
        if (!acceptingConnections.getAsBoolean()) {
            close(context, 1001, "Adapter is stopping");
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
                        close(context, 1008, "Worker route verification failed");
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
            close(
                    context,
                    1008,
                    "Only one pending Worker result is allowed"
            );
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
            connections.close(
                    stoppingWorkerId,
                    opened,
                    ADAPTER_STOPPING
            );
            return false;
        }
        return true;
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
