package com.xa.mass.workerdelivery.adapter.netty.internal.network;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionHandlerFactory;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Owns one Adapter listener, EventLoop, and all accepted physical Channels. */
public final class NettyServerLifecycle implements AutoCloseable {

    private final String adapterId;
    private final String listenHost;
    private final int listenPort;
    private final Duration shutdownTimeout;
    private final AdapterNetworkProtocol networkProtocol;
    private final WorkerConnectionHandlerFactory handlers;
    private final BooleanSupplier acceptingConnections;
    private final Set<Channel> childChannels = ConcurrentHashMap.newKeySet();
    private EventLoopGroup eventLoopGroup;
    private Channel listener;
    private boolean closed;

    public NettyServerLifecycle(
            String adapterId,
            String listenHost,
            int listenPort,
            Duration shutdownTimeout,
            AdapterNetworkProtocol networkProtocol,
            WorkerConnectionHandlerFactory handlers,
            BooleanSupplier acceptingConnections
    ) {
        this.adapterId = Objects.requireNonNull(adapterId, "adapterId");
        this.listenHost = Objects.requireNonNull(listenHost, "listenHost");
        this.listenPort = listenPort;
        this.shutdownTimeout = Objects.requireNonNull(
                shutdownTimeout,
                "shutdownTimeout"
        );
        this.networkProtocol = Objects.requireNonNull(
                networkProtocol,
                "networkProtocol"
        );
        this.handlers = Objects.requireNonNull(handlers, "handlers");
        this.acceptingConnections = Objects.requireNonNull(
                acceptingConnections,
                "acceptingConnections"
        );
    }

    public void start() {
        if (eventLoopGroup != null || listener != null || closed) {
            throw new IllegalStateException(
                    "Netty network server cannot be started again"
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
                        networkProtocol.installPipeline(
                                channel,
                                handlers,
                                acceptingConnections
                        );
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
                    "netty.startListener",
                    "Adapter listener start was interrupted",
                    error
            );
        } catch (Exception error) {
            throw new WorkerDeliveryAdapterException(
                    WorkerDeliveryAdapterErrorCode.LISTENER_START_FAILED,
                    "netty.startListener",
                    "Could not bind Adapter " + adapterId,
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
                networkProtocol.close(
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
