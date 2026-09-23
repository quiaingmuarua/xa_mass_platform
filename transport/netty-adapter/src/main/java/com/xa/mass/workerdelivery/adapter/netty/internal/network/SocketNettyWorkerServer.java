package com.xa.mass.workerdelivery.adapter.netty.internal.network;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.LineEncoder;
import io.netty.handler.codec.string.LineSeparator;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.timeout.WriteTimeoutHandler;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Complete UTF-8 line-Socket physical server for one Adapter instance. */
public final class SocketNettyWorkerServer implements NettyWorkerServer {

    private static final int MAX_FRAME_BYTES = 1_048_576;
    private static final int MIN_CHILD_EVENT_LOOP_THREADS = 4;
    private static final int ACCEPT_BACKLOG = 4_096;

    private final String adapterId;
    private final String listenHost;
    private final int listenPort;
    private final Duration sendTimeLimit;
    private final Duration shutdownTimeout;
    private final Set<Channel> childChannels = ConcurrentHashMap.newKeySet();
    private EventLoopGroup acceptorEventLoopGroup;
    private EventLoopGroup childEventLoopGroup;
    private Channel listener;
    private volatile boolean closed;

    public SocketNettyWorkerServer(
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
        if (acceptorEventLoopGroup != null
                || childEventLoopGroup != null
                || listener != null
                || closed) {
            throw new IllegalStateException(
                    "Socket Worker server cannot be started again"
            );
        }

        try {
            acceptorEventLoopGroup = new MultiThreadIoEventLoopGroup(
                    1,
                    daemonThreadFactory(adapterId + "-socket-acceptor"),
                    NioIoHandler.newFactory()
            );
            childEventLoopGroup = new MultiThreadIoEventLoopGroup(
                    defaultChildEventLoopThreads(),
                    daemonThreadFactory(adapterId + "-socket-io"),
                    NioIoHandler.newFactory()
            );
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(
                            acceptorEventLoopGroup,
                            childEventLoopGroup
                    )
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, ACCEPT_BACKLOG)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            track(channel);
                            channel.pipeline()
                                    .addLast(new LineBasedFrameDecoder(
                                            MAX_FRAME_BYTES,
                                            true,
                                            true
                                    ))
                                    .addLast(new StringDecoder(
                                            StandardCharsets.UTF_8
                                    ))
                                    .addLast(new LineEncoder(
                                            LineSeparator.UNIX,
                                            StandardCharsets.UTF_8
                                    ))
                                    .addLast(new WriteTimeoutHandler(
                                            sendTimeLimit.toMillis(),
                                            TimeUnit.MILLISECONDS
                                    ))
                                    .addLast(sharedConnectionHandler);
                        }
                    });
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
            write = channel.writeAndFlush(message);
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
            channel.writeAndFlush(message)
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
        try {
            channel.close();
        } catch (RuntimeException ignored) {
            // Physical Channel teardown is best effort.
        }
    }

    @Override
    public void close() {
        EventLoopGroup stoppingChildGroup;
        EventLoopGroup stoppingAcceptorGroup;
        Channel stoppingListener;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            stoppingChildGroup = childEventLoopGroup;
            stoppingAcceptorGroup = acceptorEventLoopGroup;
            stoppingListener = listener;
            childEventLoopGroup = null;
            acceptorEventLoopGroup = null;
            listener = null;
        }

        long deadline = System.nanoTime() + shutdownTimeout.toNanos();
        RuntimeException failure = null;
        failure = closeListener(stoppingListener, deadline, failure);
        failure = closeChildChannels(deadline, failure);
        failure = stopEventLoop(
                stoppingChildGroup,
                deadline,
                failure,
                "Socket Worker child EventLoop"
        );
        failure = stopEventLoop(
                stoppingAcceptorGroup,
                deadline,
                failure,
                "Socket Worker acceptor EventLoop"
        );
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
        if (closed) {
            closeConnection(
                    channel,
                    AdapterConnectionCloseReason.ADAPTER_STOPPING
            );
        }
    }

    private RuntimeException closeChildChannels(
            long deadline,
            RuntimeException failure
    ) {
        try {
            for (Channel channel : Set.copyOf(childChannels)) {
                closeConnection(
                        channel,
                        AdapterConnectionCloseReason.ADAPTER_STOPPING
                );
            }
            boolean timedOut = false;
            for (Channel channel : Set.copyOf(childChannels)) {
                ChannelFuture closeFuture = channel.closeFuture();
                if (!awaitUntil(closeFuture, deadline)) {
                    timedOut = true;
                }
                if (channel.isOpen()) {
                    closeBestEffort(channel);
                }
                if (closeFuture.isDone() && !closeFuture.isSuccess()) {
                    failure = accumulateFutureFailure(
                            failure,
                            closeFuture
                    );
                }
            }
            if (timedOut) {
                failure = accumulate(
                        failure,
                        shutdownTimeout(
                                "netty.closeConnections",
                                "Socket Worker connections"
                        )
                );
            }
            return failure;
        } catch (RuntimeException error) {
            return accumulate(failure, error);
        }
    }

    private RuntimeException stopEventLoop(
            EventLoopGroup group,
            long deadline,
            RuntimeException failure,
            String resource
    ) {
        if (group == null) {
            return failure;
        }
        try {
            long remaining = Math.max(0, deadline - System.nanoTime());
            var termination = group.shutdownGracefully(
                    0,
                    remaining,
                    TimeUnit.NANOSECONDS
            );
            if (!awaitUntil(termination, deadline)) {
                group.shutdownGracefully(0, 0, TimeUnit.NANOSECONDS);
                return accumulate(
                        failure,
                        shutdownTimeout(
                                "netty.stopEventLoop",
                                resource
                        )
                );
            }
            return accumulateFutureFailure(failure, termination);
        } catch (RuntimeException error) {
            return accumulate(failure, error);
        }
    }

    private RuntimeException closeListener(
            Channel channel,
            long deadline,
            RuntimeException failure
    ) {
        if (channel == null) {
            return failure;
        }
        try {
            ChannelFuture closeFuture = channel.close();
            if (!awaitUntil(closeFuture, deadline)) {
                closeBestEffort(channel);
                return accumulate(
                        failure,
                        shutdownTimeout(
                                "netty.closeListener",
                                "Socket Worker listener"
                        )
                );
            }
            return accumulateFutureFailure(failure, closeFuture);
        } catch (RuntimeException error) {
            return accumulate(failure, error);
        }
    }

    private static boolean awaitUntil(
            io.netty.util.concurrent.Future<?> future,
            long deadline
    ) {
        if (future.isDone()) {
            return true;
        }
        long remaining = deadline - System.nanoTime();
        return remaining > 0 && future.awaitUninterruptibly(
                remaining,
                TimeUnit.NANOSECONDS
        );
    }

    private static RuntimeException accumulateFutureFailure(
            RuntimeException failure,
            io.netty.util.concurrent.Future<?> future
    ) {
        Throwable cause = future.cause();
        if (future.isSuccess() || cause == null) {
            return failure;
        }
        RuntimeException error = cause instanceof RuntimeException runtime
                ? runtime
                : new RuntimeException(cause);
        return accumulate(failure, error);
    }

    private WorkerDeliveryAdapterException shutdownTimeout(
            String operation,
            String resource
    ) {
        return new WorkerDeliveryAdapterException(
                WorkerDeliveryAdapterErrorCode.SHUTDOWN_TIMEOUT,
                operation,
                resource + " did not stop within the Adapter "
                        + adapterId + " owner budget",
                null
        );
    }

    private static void closeBestEffort(Channel channel) {
        try {
            channel.close();
        } catch (RuntimeException ignored) {
            // Physical Channel teardown is best effort after the deadline.
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

    private static int defaultChildEventLoopThreads() {
        return Math.max(
                MIN_CHILD_EVENT_LOOP_THREADS,
                Runtime.getRuntime().availableProcessors() * 2
        );
    }
}
