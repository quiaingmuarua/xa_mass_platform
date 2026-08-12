package com.xa.mass.workerdelivery.adapter.netty.internal.network;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;

import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionHandlerFactory;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

final class WebSocketNetworkProtocol implements AdapterNetworkProtocol {

    private static final int MAX_FRAME_BYTES = 1_048_576;
    private static final String WORKER_WEBSOCKET_PATH =
            "/api/v1/worker-delivery/websocket";

    private final Duration sendTimeLimit;

    WebSocketNetworkProtocol(Duration sendTimeLimit) {
        this.sendTimeLimit = Objects.requireNonNull(
                sendTimeLimit,
                "sendTimeLimit"
        );
    }

    @Override
    public void installPipeline(
            SocketChannel channel,
            WorkerConnectionHandlerFactory handlers,
            BooleanSupplier acceptingConnections
    ) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(handlers, "handlers");
        Objects.requireNonNull(acceptingConnections, "acceptingConnections");
        channel.pipeline()
                .addLast(new HttpServerCodec())
                .addLast(new WriteTimeoutHandler(
                        sendTimeLimit.toMillis(),
                        TimeUnit.MILLISECONDS
                ))
                .addLast(new HttpObjectAggregator(MAX_FRAME_BYTES))
                .addLast(new WebSocketServerProtocolHandler(
                        WORKER_WEBSOCKET_PATH,
                        null,
                        false,
                        MAX_FRAME_BYTES,
                        false,
                        false
                ))
                .addLast(new UnmatchedRequestHandler())
                .addLast(new HandshakeAdmissionHandler(
                        this,
                        acceptingConnections
                ))
                .addLast(new WebSocketStringCodec(this))
                .addLast(handlers.newIdentityHandler());
    }

    @Override
    public void close(Channel channel, AdapterConnectionCloseReason reason) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(reason, "reason");
        if (!channel.isOpen()) {
            return;
        }
        CloseDescription description = describe(reason);
        try {
            channel.writeAndFlush(new CloseWebSocketFrame(
                    description.code(),
                    description.message()
            )).addListener(ChannelFutureListener.CLOSE);
        } catch (RuntimeException ignored) {
            closeBestEffort(channel);
        }
    }

    private static CloseDescription describe(
            AdapterConnectionCloseReason reason
    ) {
        return switch (reason) {
            case ADAPTER_STOPPING -> new CloseDescription(
                    1001,
                    "Adapter is stopping"
            );
            case BINARY_UNSUPPORTED -> new CloseDescription(
                    1003,
                    "Binary frames are unsupported"
            );
            case INVALID_REPORT -> new CloseDescription(
                    1007,
                    "Invalid Worker result"
            );
            case IDENTITY_REQUIRED -> new CloseDescription(
                    1008,
                    "Worker must identify first"
            );
            case VERIFICATION_IN_PROGRESS -> new CloseDescription(
                    1008,
                    "Worker route verification is in progress"
            );
            case VERIFICATION_FAILED -> new CloseDescription(
                    1008,
                    "Worker route verification failed"
            );
            case REPLACED -> new CloseDescription(
                    1008,
                    "Replaced by a newer Worker connection"
            );
            case RESULT_BUFFER_FULL -> new CloseDescription(
                    1013,
                    "Worker result buffer is full"
            );
            case TRANSPORT_ERROR -> new CloseDescription(
                    1011,
                    "Worker transport failed"
            );
        };
    }

    private static void closeBestEffort(Channel channel) {
        try {
            channel.close();
        } catch (RuntimeException ignored) {
            // Physical Channel teardown is best effort.
        }
    }

    private record CloseDescription(int code, String message) {
    }

    private static final class HandshakeAdmissionHandler
            extends ChannelInboundHandlerAdapter {

        private final AdapterNetworkProtocol protocol;
        private final BooleanSupplier acceptingConnections;

        private HandshakeAdmissionHandler(
                AdapterNetworkProtocol protocol,
                BooleanSupplier acceptingConnections
        ) {
            this.protocol = protocol;
            this.acceptingConnections = acceptingConnections;
        }

        @Override
        public void userEventTriggered(
                ChannelHandlerContext context,
                Object event
        ) {
            if (event
                    instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
                if (!acceptingConnections.getAsBoolean()) {
                    protocol.close(
                            context.channel(),
                            AdapterConnectionCloseReason.ADAPTER_STOPPING
                    );
                }
                return;
            }
            context.fireUserEventTriggered(event);
        }
    }

    private static final class WebSocketStringCodec
            extends MessageToMessageCodec<WebSocketFrame, String> {

        private final AdapterNetworkProtocol protocol;

        private WebSocketStringCodec(AdapterNetworkProtocol protocol) {
            this.protocol = protocol;
        }

        @Override
        protected void encode(
                ChannelHandlerContext context,
                String message,
                List<Object> output
        ) {
            output.add(new TextWebSocketFrame(message));
        }

        @Override
        protected void decode(
                ChannelHandlerContext context,
                WebSocketFrame frame,
                List<Object> output
        ) {
            if (frame instanceof TextWebSocketFrame text) {
                output.add(text.text());
                return;
            }
            if (frame instanceof BinaryWebSocketFrame) {
                protocol.close(
                        context.channel(),
                        AdapterConnectionCloseReason.BINARY_UNSUPPORTED
                );
            }
        }
    }

    private static final class UnmatchedRequestHandler
            extends SimpleChannelInboundHandler<FullHttpRequest> {

        @Override
        protected void channelRead0(
                ChannelHandlerContext context,
                FullHttpRequest request
        ) {
            var response = new DefaultFullHttpResponse(
                    request.protocolVersion(),
                    NOT_FOUND
            );
            response.headers().setInt(CONTENT_LENGTH, 0);
            context.writeAndFlush(response)
                    .addListener(ChannelFutureListener.CLOSE);
        }
    }
}
