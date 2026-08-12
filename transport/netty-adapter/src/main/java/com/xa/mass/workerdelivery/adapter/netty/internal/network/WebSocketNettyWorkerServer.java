package com.xa.mass.workerdelivery.adapter.netty.internal.network;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
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
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Complete WebSocket physical server for one Adapter instance. */
public final class WebSocketNettyWorkerServer
        implements NettyWorkerServer {

    private static final int MAX_FRAME_BYTES = 1_048_576;
    private static final String WORKER_WEBSOCKET_PATH =
            "/api/v1/worker-delivery/websocket";

    private final String adapterId;
    private final String listenHost;
    private final int listenPort;
    private final Duration sendTimeLimit;
    private final Duration shutdownTimeout;
    private final Set<Channel> childChannels = ConcurrentHashMap.newKeySet();
    private EventLoopGroup eventLoopGroup;
    private Channel listener;
    private boolean closed;

    public WebSocketNettyWorkerServer(
            String adapterId,
            String listenHost,
            int listenPort,
            Duration sendTimeLimit,
            Duration shutdownTimeout
    ) {
        this.adapterId = Objects.requireNonNull(adapterId, "adapterId");
        this.listenHost = Objects.requireNonNull(listenHost, "listenHost");
        this.listenPort = listenPort;
        this.sendTimeLimit = Objects.requireNonNull(
                sendTimeLimit,
                "sendTimeLimit"
        );
        this.shutdownTimeout = Objects.requireNonNull(
                shutdownTimeout,
                "shutdownTimeout"
        );
    }

    @Override
    public synchronized void start(ChannelHandler sharedConnectionHandler) {
        requireSharable(sharedConnectionHandler);
        if (eventLoopGroup != null || listener != null || closed) {
            throw new IllegalStateException(
                    "WebSocket Worker server cannot be started again"
            );
        }

        eventLoopGroup = new MultiThreadIoEventLoopGroup(
                1,
                daemonThreadFactory(adapterId + "-websocket-netty"),
                NioIoHandler.newFactory()
        );
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(eventLoopGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        track(channel);
                        channel.pipeline()
                                .addLast(new HttpServerCodec())
                                .addLast(new WriteTimeoutHandler(
                                        sendTimeLimit.toMillis(),
                                        TimeUnit.MILLISECONDS
                                ))
                                .addLast(new HttpObjectAggregator(
                                        MAX_FRAME_BYTES
                                ))
                                .addLast(new WebSocketServerProtocolHandler(
                                        WORKER_WEBSOCKET_PATH,
                                        null,
                                        false,
                                        MAX_FRAME_BYTES,
                                        false,
                                        false
                                ))
                                .addLast(new UnmatchedRequestHandler())
                                .addLast(new WebSocketStringCodec(
                                        WebSocketNettyWorkerServer.this
                                ))
                                .addLast(sharedConnectionHandler);
                    }
                });

        try {
            listener = bootstrap.bind(new InetSocketAddress(
                    listenHost,
                    listenPort
            )).sync().channel();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw failStart(new WorkerDeliveryAdapterException(
                    WorkerDeliveryAdapterErrorCode.LISTENER_START_FAILED,
                    "netty.startListener",
                    "Adapter listener start was interrupted",
                    error
            ));
        } catch (Exception error) {
            throw failStart(new WorkerDeliveryAdapterException(
                    WorkerDeliveryAdapterErrorCode.LISTENER_START_FAILED,
                    "netty.startListener",
                    "Could not bind Adapter " + adapterId,
                    error
            ));
        }
    }

    @Override
    public TextWriteAttempt writeText(Channel channel, String message) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(message, "message");
        if (!childChannels.contains(channel) || !channel.isActive()) {
            return TextWriteAttempt.RETRY_LATER;
        }
        if (!channel.isWritable()) {
            return TextWriteAttempt.RETRY_LATER;
        }

        ChannelFuture write;
        try {
            write = channel.writeAndFlush(new TextWebSocketFrame(message));
        } catch (RuntimeException error) {
            closeConnection(
                    channel,
                    AdapterConnectionCloseReason.TRANSPORT_ERROR
            );
            return TextWriteAttempt.UNKNOWN;
        }
        write.addListener(future -> {
            if (!future.isSuccess()) {
                closeConnection(
                        channel,
                        AdapterConnectionCloseReason.TRANSPORT_ERROR
                );
            }
        });
        return TextWriteAttempt.STARTED;
    }

    @Override
    public void writeTextAndClose(
            Channel channel,
            String message,
            AdapterConnectionCloseReason reason
    ) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(reason, "reason");
        if (!channel.isOpen()) {
            return;
        }
        try {
            channel.writeAndFlush(new TextWebSocketFrame(message))
                    .addListener(ignored -> closeConnection(channel, reason));
        } catch (RuntimeException error) {
            closeConnection(channel, reason);
        }
    }

    @Override
    public void closeConnection(
            Channel channel,
            AdapterConnectionCloseReason reason
    ) {
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

    @Override
    public int trackedConnectionCount() {
        return childChannels.size();
    }

    @Override
    public void close() {
        EventLoopGroup stoppingGroup;
        Channel stoppingListener;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            stoppingGroup = eventLoopGroup;
            stoppingListener = listener;
            eventLoopGroup = null;
            listener = null;
        }

        RuntimeException failure = null;
        failure = closeListener(stoppingListener, failure);
        failure = closeChildChannels(failure);
        failure = stopEventLoop(stoppingGroup, failure);
        if (failure != null) {
            throw failure;
        }
    }

    private RuntimeException failStart(RuntimeException failure) {
        try {
            close();
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
        return failure;
    }

    private void track(Channel channel) {
        childChannels.add(channel);
        channel.closeFuture().addListener(ignored ->
                childChannels.remove(channel)
        );
    }

    private RuntimeException closeChildChannels(RuntimeException failure) {
        try {
            for (Channel channel : Set.copyOf(childChannels)) {
                closeConnection(
                        channel,
                        AdapterConnectionCloseReason.ADAPTER_STOPPING
                );
            }
            long deadline = System.nanoTime() + shutdownTimeout.toNanos();
            for (Channel channel : Set.copyOf(childChannels)) {
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) {
                    channel.closeFuture().awaitUninterruptibly(
                            remaining,
                            TimeUnit.NANOSECONDS
                    );
                }
                if (channel.isOpen()) {
                    channel.close().syncUninterruptibly();
                }
            }
            return failure;
        } catch (RuntimeException error) {
            return accumulate(failure, error);
        }
    }

    private RuntimeException stopEventLoop(
            EventLoopGroup group,
            RuntimeException failure
    ) {
        if (group == null) {
            return failure;
        }
        try {
            group.shutdownGracefully(
                    0,
                    shutdownTimeout.toMillis(),
                    TimeUnit.MILLISECONDS
            ).syncUninterruptibly();
            return failure;
        } catch (RuntimeException error) {
            return accumulate(failure, error);
        }
    }

    private static RuntimeException closeListener(
            Channel channel,
            RuntimeException failure
    ) {
        if (channel == null) {
            return failure;
        }
        try {
            channel.close().syncUninterruptibly();
            return failure;
        } catch (RuntimeException error) {
            return accumulate(failure, error);
        }
    }

    private static void requireSharable(ChannelHandler handler) {
        Objects.requireNonNull(handler, "sharedConnectionHandler");
        if (!(handler instanceof ChannelHandlerAdapter adapter)
                || !adapter.isSharable()) {
            throw new IllegalArgumentException(
                    "sharedConnectionHandler must be @Sharable"
            );
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

    private static RuntimeException accumulate(
            RuntimeException current,
            RuntimeException addition
    ) {
        if (current == null) {
            return addition;
        }
        current.addSuppressed(addition);
        return current;
    }

    private static java.util.concurrent.ThreadFactory daemonThreadFactory(
            String prefix
    ) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "worker-delivery-" + prefix + "-"
                            + sequence.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        };
    }

    private record CloseDescription(int code, String message) {
    }

    private static final class WebSocketStringCodec
            extends MessageToMessageCodec<WebSocketFrame, String> {

        private final WebSocketNettyWorkerServer server;

        private WebSocketStringCodec(WebSocketNettyWorkerServer server) {
            this.server = server;
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
                server.closeConnection(
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
