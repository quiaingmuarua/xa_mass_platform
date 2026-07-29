package com.xa.mass.workerdelivery.adapter.socket;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SYSTEM_POLLING_ENDPOINT_MANAGER_ID;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterState;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.adapter.dispatch.WorkerDeliveryAdapterCore;
import com.xa.mass.workerdelivery.adapter.message.BoundedWorkerResultBuffer;
import com.xa.mass.workerdelivery.adapter.message.TaskItemResultMessageHandler;
import com.xa.mass.workerdelivery.adapter.message.WorkerConnectionMessageDispatcher;
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
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class SocketWorkerDeliveryAdapter
        implements WorkerDeliveryAdapter {

    private static final int MAX_LINE_BYTES = 1_048_576;
    private static final System.Logger LOGGER = System.getLogger(
            SocketWorkerDeliveryAdapter.class.getName()
    );

    private final String adapterId;
    private final String listenHost;
    private final int listenPort;
    private final Duration dispatchInterval;
    private final int deliveryParallelism;
    private final Duration shutdownTimeout;
    private final WorkerDeliveryCodec codec;
    private final NettySocketWorkerConnectionRegistry connections;
    private final WorkerDeliveryAdapterCore core;
    private final WorkerConnectionMessageDispatcher messageDispatcher;
    private volatile WorkerDeliveryAdapterState state =
            WorkerDeliveryAdapterState.REGISTERED;
    private EventLoopGroup eventLoopGroup;
    private Channel listener;
    private ScheduledExecutorService scheduler;
    private ExecutorService deliveryExecutor;

    public SocketWorkerDeliveryAdapter(
            String adapterId,
            WorkerDeliveryGatewayClient gateway,
            WorkerDeliveryCodec codec,
            String listenHost,
            int listenPort,
            Duration dispatchInterval,
            int commandConsumeLimit,
            int deliveryParallelism,
            int resultBatchSize,
            int resultBufferCapacity,
            Duration sendTimeLimit,
            Duration shutdownTimeout
    ) {
        this.adapterId = requireAdapterId(adapterId);
        this.codec = Objects.requireNonNull(codec, "codec");
        if (listenHost == null || listenHost.isBlank()) {
            throw new IllegalArgumentException(
                    "listenHost must be non-blank"
            );
        }
        if (listenPort < 1 || listenPort > 65_535) {
            throw new IllegalArgumentException(
                    "listenPort must be between 1 and 65535"
            );
        }
        requirePositive(dispatchInterval, "dispatchInterval");
        requirePositive(sendTimeLimit, "sendTimeLimit");
        requirePositive(shutdownTimeout, "shutdownTimeout");
        if (commandConsumeLimit <= 0
                || deliveryParallelism <= 0
                || resultBatchSize <= 0
                || resultBufferCapacity <= 0) {
            throw new IllegalArgumentException(
                    "Adapter bounds must be positive"
            );
        }
        this.listenHost = listenHost;
        this.listenPort = listenPort;
        this.dispatchInterval = dispatchInterval;
        this.deliveryParallelism = deliveryParallelism;
        this.shutdownTimeout = shutdownTimeout;
        connections = new NettySocketWorkerConnectionRegistry(
                codec,
                sendTimeLimit
        );
        BoundedWorkerResultBuffer resultBuffer =
                new BoundedWorkerResultBuffer(resultBufferCapacity);
        messageDispatcher = new WorkerConnectionMessageDispatcher(
                List.of(new TaskItemResultMessageHandler(resultBuffer))
        );
        core = new WorkerDeliveryAdapterCore(
                Objects.requireNonNull(gateway, "gateway"),
                codec,
                connections,
                adapterId,
                commandConsumeLimit,
                resultBatchSize,
                resultBuffer
        );
    }

    @Override
    public String adapterId() {
        return adapterId;
    }

    @Override
    public WorkerDeliveryAdapterState state() {
        return state;
    }

    public String listenHost() {
        return listenHost;
    }

    public int listenPort() {
        return listenPort;
    }

    @Override
    public synchronized void start() {
        if (state == WorkerDeliveryAdapterState.RUNNING) {
            return;
        }
        if (state != WorkerDeliveryAdapterState.REGISTERED) {
            throw new IllegalStateException(
                    "Cannot start Adapter from state " + state
            );
        }
        try {
            initializeExecutors();
            startListener();
            state = WorkerDeliveryAdapterState.RUNNING;
            scheduler.scheduleWithFixedDelay(
                    this::dispatchSafely,
                    0,
                    dispatchInterval.toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (RuntimeException error) {
            RuntimeException failure = classify(
                    error,
                    WorkerDeliveryAdapterErrorCode.LISTENER_START_FAILED,
                    "socket.start",
                    "Socket Adapter could not start"
            );
            try {
                close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    @Override
    public void close() {
        ScheduledExecutorService stoppingScheduler;
        ExecutorService stoppingDeliveries;
        Channel stoppingListener;
        EventLoopGroup stoppingEventLoopGroup;
        synchronized (this) {
            if (state == WorkerDeliveryAdapterState.CLOSED) {
                return;
            }
            state = WorkerDeliveryAdapterState.STOPPING;
            stoppingScheduler = scheduler;
            stoppingDeliveries = deliveryExecutor;
            stoppingListener = listener;
            stoppingEventLoopGroup = eventLoopGroup;
            scheduler = null;
            deliveryExecutor = null;
            listener = null;
            eventLoopGroup = null;
        }

        RuntimeException failure = null;
        failure = stopScheduler(stoppingScheduler, failure);
        failure = closeListener(stoppingListener, failure);
        failure = stopExecutor(stoppingDeliveries, failure);
        try {
            core.close();
        } catch (RuntimeException error) {
            failure = accumulate(failure, error);
        }
        try {
            connections.closeAll();
        } catch (RuntimeException error) {
            failure = accumulate(failure, error);
        }
        failure = stopEventLoop(stoppingEventLoopGroup, failure);

        synchronized (this) {
            state = WorkerDeliveryAdapterState.CLOSED;
        }
        if (failure != null) {
            throw classify(
                    failure,
                    WorkerDeliveryAdapterErrorCode.SHUTDOWN_INTERRUPTED,
                    "socket.close",
                    "Socket Adapter could not close cleanly"
            );
        }
    }

    int activeConnectionCount() {
        return connections.activeConnectionCount();
    }

    private void initializeExecutors() {
        scheduler = Executors.newSingleThreadScheduledExecutor(
                daemonThreadFactory(adapterId + "-mailbox")
        );
        deliveryExecutor = Executors.newFixedThreadPool(
                deliveryParallelism,
                daemonThreadFactory(adapterId + "-delivery")
        );
    }

    private void startListener() {
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
                        channel.pipeline()
                                .addLast(new LineBasedFrameDecoder(
                                        MAX_LINE_BYTES,
                                        true,
                                        true
                                ))
                                .addLast(new StringDecoder(
                                        StandardCharsets.UTF_8
                                ))
                                .addLast(new StringEncoder(
                                        StandardCharsets.UTF_8
                                ))
                                .addLast(new SocketWorkerHandler(
                                        connections,
                                        codec,
                                        messageDispatcher,
                                        () -> state
                                                == WorkerDeliveryAdapterState
                                                .RUNNING
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
                    "socket.startListener",
                    "Socket listener start was interrupted",
                    error
            );
        } catch (Exception error) {
            throw new WorkerDeliveryAdapterException(
                    WorkerDeliveryAdapterErrorCode.LISTENER_START_FAILED,
                    "socket.startListener",
                    "Could not bind Socket Adapter " + adapterId,
                    error
            );
        }
    }

    private void dispatchSafely() {
        if (state != WorkerDeliveryAdapterState.RUNNING) {
            return;
        }
        ExecutorService current = deliveryExecutor;
        if (current == null) {
            return;
        }
        try {
            core.dispatchOnce(current);
        } catch (RuntimeException error) {
            WorkerDeliveryAdapterException failure = classify(
                    error,
                    WorkerDeliveryAdapterErrorCode.DELIVERY_INTERRUPTED,
                    "socket.dispatchRound",
                    "Socket Adapter dispatch round failed"
            );
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "errorCode={0} operation={1} adapterId={2} message={3}",
                    failure.errorCode().code(),
                    failure.operation(),
                    adapterId,
                    failure.getMessage()
            );
        }
    }

    private RuntimeException stopScheduler(
            ScheduledExecutorService executor,
            RuntimeException failure
    ) {
        if (executor == null) {
            return failure;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(
                    shutdownTimeout.toMillis(),
                    TimeUnit.MILLISECONDS
            )) {
                executor.shutdownNow();
                executor.awaitTermination(
                        shutdownTimeout.toMillis(),
                        TimeUnit.MILLISECONDS
                );
            }
            return failure;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            return accumulate(
                    failure,
                    new WorkerDeliveryAdapterException(
                            WorkerDeliveryAdapterErrorCode
                                    .SHUTDOWN_INTERRUPTED,
                            "socket.stopScheduler",
                            "Adapter scheduler shutdown was interrupted",
                            error
                    )
            );
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

    private RuntimeException stopExecutor(
            ExecutorService executor,
            RuntimeException failure
    ) {
        if (executor == null) {
            return failure;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(
                    shutdownTimeout.toMillis(),
                    TimeUnit.MILLISECONDS
            )) {
                executor.shutdownNow();
                executor.awaitTermination(
                        shutdownTimeout.toMillis(),
                        TimeUnit.MILLISECONDS
                );
            }
            return failure;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            return accumulate(
                    failure,
                    new WorkerDeliveryAdapterException(
                            WorkerDeliveryAdapterErrorCode
                                    .SHUTDOWN_INTERRUPTED,
                            "socket.stopDeliveryExecutor",
                            "Delivery executor shutdown was interrupted",
                            error
                    )
            );
        }
    }

    private static WorkerDeliveryAdapterException classify(
            RuntimeException error,
            WorkerDeliveryAdapterErrorCode errorCode,
            String operation,
            String message
    ) {
        if (error instanceof WorkerDeliveryAdapterException classified) {
            return classified;
        }
        return new WorkerDeliveryAdapterException(
                errorCode,
                operation,
                message,
                error
        );
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

    private static java.util.concurrent.ThreadFactory
    daemonThreadFactory(String prefix) {
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

    private static String requireAdapterId(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException(
                    "adapterId must be non-blank"
            );
        }
        if (SYSTEM_POLLING_ENDPOINT_MANAGER_ID.equals(adapterId)) {
            throw new IllegalArgumentException(
                    "system-polling cannot own an active Adapter"
            );
        }
        return adapterId;
    }

    private static void requirePositive(
            Duration value,
            String name
    ) {
        if (value == null
                || value.isZero()
                || value.isNegative()
                || value.toMillis() <= 0) {
            throw new IllegalArgumentException(
                    name + " must be positive"
            );
        }
    }
}
