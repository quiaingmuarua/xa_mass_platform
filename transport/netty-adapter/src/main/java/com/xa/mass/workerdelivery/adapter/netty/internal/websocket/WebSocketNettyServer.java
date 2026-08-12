package com.xa.mass.workerdelivery.adapter.netty.internal.websocket;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.adapter.netty.internal.gateway.BoundedDeliveryReportQueue;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public final class WebSocketNettyServer implements AutoCloseable {

    private static final int MAX_FRAME_BYTES = 1_048_576;
    private static final String WORKER_WEBSOCKET_PATH =
            "/api/v1/worker-delivery/websocket";

    private final String adapterId;
    private final String listenHost;
    private final int listenPort;
    private final Duration sendTimeLimit;
    private final Duration shutdownTimeout;
    private final WebSocketBoundWorkerDirectory connections;
    private final WorkerDeliveryCodec codec;
    private final BoundedDeliveryReportQueue reportQueue;
    private final WorkerDeliveryGatewayClient gateway;
    private final BooleanSupplier acceptingConnections;
    private final Set<Channel> childChannels =
            ConcurrentHashMap.newKeySet();
    private EventLoopGroup eventLoopGroup;
    private Channel listener;
    private boolean closed;

    public WebSocketNettyServer(
            String adapterId,
            String listenHost,
            int listenPort,
            Duration sendTimeLimit,
            Duration shutdownTimeout,
            WebSocketBoundWorkerDirectory connections,
            WorkerDeliveryCodec codec,
            BoundedDeliveryReportQueue reportQueue,
            WorkerDeliveryGatewayClient gateway,
            BooleanSupplier acceptingConnections
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
        this.connections = Objects.requireNonNull(connections, "connections");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.reportQueue = Objects.requireNonNull(reportQueue, "reportQueue");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.acceptingConnections = Objects.requireNonNull(
                acceptingConnections,
                "acceptingConnections"
        );
    }

    public void start() {
        if (eventLoopGroup != null || listener != null || closed) {
            throw new IllegalStateException(
                    "WebSocket network server cannot be started again"
            );
        }
        eventLoopGroup = new MultiThreadIoEventLoopGroup(
                1,
                daemonThreadFactory(adapterId + "-netty"),
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
                                .addLast(
                                        new UnmatchedWebSocketRequestHandler()
                                )
                                .addLast(new WebSocketWorkerIdentityHandler(
                                        connections,
                                        codec,
                                        reportQueue,
                                        gateway,
                                        adapterId,
                                        sendTimeLimit,
                                        acceptingConnections
                                ));
                    }
                });
        try {
            listener = bootstrap.bind(new InetSocketAddress(
                    listenHost,
                    listenPort
            )).sync().channel();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new WorkerDeliveryAdapterException(
                    WorkerDeliveryAdapterErrorCode.LISTENER_START_FAILED,
                    "websocket.startListener",
                    "WebSocket listener start was interrupted",
                    error
            );
        } catch (Exception error) {
            throw new WorkerDeliveryAdapterException(
                    WorkerDeliveryAdapterErrorCode.LISTENER_START_FAILED,
                    "websocket.startListener",
                    "Could not bind WebSocket Adapter " + adapterId,
                    error
            );
        }
    }

    public String listenHost() {
        return listenHost;
    }

    public int listenPort() {
        return listenPort;
    }

    public int trackedConnectionCount() {
        return childChannels.size();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        RuntimeException failure = null;
        failure = closeListener(listener, failure);
        listener = null;
        failure = closeChildChannels(failure);
        failure = stopEventLoop(eventLoopGroup, failure);
        eventLoopGroup = null;
        if (failure != null) {
            throw failure;
        }
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
                WebSocketCloseReason.ADAPTER_STOPPING.close(channel);
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
}
