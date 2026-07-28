package com.xa.mass.workerdelivery.adapter.websocket;

import static com.xa.mass.workerdelivery.adapter.message.WorkerMessageHandlingResult.ACCEPTED;
import static com.xa.mass.workerdelivery.adapter.message.WorkerMessageHandlingResult.ADAPTER_CLOSED;
import static com.xa.mass.workerdelivery.adapter.message.WorkerMessageHandlingResult.BUFFER_FULL;
import static com.xa.mass.workerdelivery.adapter.websocket.WorkerConnectionRegistry.ConnectionCloseReason.ADAPTER_STOPPING;
import static com.xa.mass.workerdelivery.adapter.websocket.WorkerConnectionRegistry.ConnectionCloseReason.RESULT_BUFFER_FULL;
import static com.xa.mass.workerdelivery.adapter.websocket.WorkerConnectionRegistry.ConnectionCloseReason.TRANSPORT_ERROR;

import com.xa.mass.workerdelivery.adapter.message.WorkerConnectionMessageDispatcher;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.BooleanSupplier;

final class WorkerWebSocketHandler
        extends SimpleChannelInboundHandler<WebSocketFrame> {

    static final String WORKER_PATH =
            "/api/v1/worker-delivery/websocket/workers";
    static final String WORKER_PATH_PREFIX = WORKER_PATH + "/";

    private final WorkerConnectionRegistry connections;
    private final WorkerDeliveryCodec codec;
    private final WorkerConnectionMessageDispatcher dispatcher;
    private final BooleanSupplier acceptingConnections;
    private String workerId;
    private Channel workerChannel;

    WorkerWebSocketHandler(
            WorkerConnectionRegistry connections,
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
    public void userEventTriggered(
            ChannelHandlerContext context,
            Object event
    ) {
        if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete
                handshake) {
            String resolvedWorkerId = parseWorkerId(
                    handshake.requestUri()
            );
            if (resolvedWorkerId == null) {
                close(context, 1008, "Invalid Worker identity");
                return;
            }
            Channel opened = context.channel();
            if (!acceptingConnections.getAsBoolean()) {
                close(context, 1001, "Adapter is stopping");
                return;
            }
            connections.bind(resolvedWorkerId, opened);
            if (!acceptingConnections.getAsBoolean()) {
                connections.close(
                        resolvedWorkerId,
                        opened,
                        ADAPTER_STOPPING
                );
                return;
            }
            workerId = resolvedWorkerId;
            workerChannel = opened;
            return;
        }
        context.fireUserEventTriggered(event);
    }

    @Override
    protected void channelRead0(
            ChannelHandlerContext context,
            WebSocketFrame frame
    ) {
        if (workerChannel == null) {
            close(context, 1008, "Worker handshake is incomplete");
            return;
        }
        if (frame instanceof TextWebSocketFrame text) {
            handleResult(context, text.text());
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
            String payload
    ) {
        var message = codec.decodeWorkerConnectionMessage(payload);
        if (message == null) {
            disconnect();
            close(context, 1007, "Invalid Worker result");
            return;
        }
        var acceptance = dispatcher.dispatch(workerId, message);
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

    private void clearConnection() {
        workerChannel = null;
        workerId = null;
    }

    private static String parseWorkerId(String requestUri) {
        try {
            String rawPath = URI.create(requestUri).getRawPath();
            if (rawPath == null
                    || !rawPath.startsWith(WORKER_PATH_PREFIX)) {
                return null;
            }
            String encoded = rawPath.substring(
                    WORKER_PATH_PREFIX.length()
            );
            if (encoded.isEmpty() || encoded.contains("/")) {
                return null;
            }
            String workerId = URLDecoder.decode(
                    encoded,
                    StandardCharsets.UTF_8
            );
            if (workerId.isBlank() || workerId.contains("/")) {
                return null;
            }
            return workerId;
        } catch (IllegalArgumentException error) {
            return null;
        }
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
