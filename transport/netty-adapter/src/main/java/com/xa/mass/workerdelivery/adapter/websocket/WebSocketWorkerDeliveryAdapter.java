package com.xa.mass.workerdelivery.adapter.websocket;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SYSTEM_POLLING_ENDPOINT_MANAGER_ID;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterState;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.adapter.dispatch.WorkerCommandLoop;
import com.xa.mass.workerdelivery.adapter.result.BoundedWorkerResultQueue;
import com.xa.mass.workerdelivery.adapter.result.WorkerResultLoop;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class WebSocketWorkerDeliveryAdapter
        implements WorkerDeliveryAdapter {

    private static final int MAX_HTTP_CONTENT_BYTES = 1_048_576;
    private static final System.Logger LOGGER = System.getLogger(
            WebSocketWorkerDeliveryAdapter.class.getName()
    );

    private final String adapterId;
    private final String listenHost;
    private final int listenPort;
    private final Duration commandLoopInterval;
    private final Duration resultSubmitInterval;
    private final Duration sendTimeLimit;
    private final Duration shutdownTimeout;
    private final WorkerDeliveryCodec codec;
    private final NettyWorkerConnectionRegistry connections;
    private final WorkerCommandLoop commandLoop;
    private final WorkerResultLoop resultLoop;
    private final BoundedWorkerResultQueue resultQueue;
    private final WorkerDeliveryGatewayClient gateway;
    private volatile WorkerDeliveryAdapterState state =
            WorkerDeliveryAdapterState.REGISTERED;
    private EventLoopGroup eventLoopGroup;
    private Channel listener;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> commandTask;
    private ScheduledFuture<?> resultTask;

    public WebSocketWorkerDeliveryAdapter(
            String adapterId,
            WorkerDeliveryGatewayClient gateway,
            String listenHost,
            int listenPort,
            Duration commandLoopInterval,
            int commandConsumeLimit,
            int commandQueueCapacity,
            Duration resultSubmitInterval,
            int resultQueueCapacity,
            Duration sendTimeLimit,
            Duration shutdownTimeout
    ) {
        this.adapterId = requireAdapterId(adapterId);
        codec = new WorkerDeliveryCodec();
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
        requirePositive(commandLoopInterval, "commandLoopInterval");
        requirePositive(resultSubmitInterval, "resultSubmitInterval");
        requirePositive(sendTimeLimit, "sendTimeLimit");
        requirePositive(shutdownTimeout, "shutdownTimeout");
        if (commandConsumeLimit <= 0 || resultQueueCapacity <= 0) {
            throw new IllegalArgumentException(
                    "Adapter bounds must be positive"
            );
        }
        if (commandQueueCapacity < commandConsumeLimit) {
            throw new IllegalArgumentException(
                    "commandQueueCapacity must be at least "
                            + "commandConsumeLimit"
            );
        }
        this.listenHost = listenHost;
        this.listenPort = listenPort;
        this.commandLoopInterval = commandLoopInterval;
        this.resultSubmitInterval = resultSubmitInterval;
        this.sendTimeLimit = sendTimeLimit;
        this.shutdownTimeout = shutdownTimeout;
        connections = new NettyWorkerConnectionRegistry(codec);
        resultQueue = new BoundedWorkerResultQueue(resultQueueCapacity);
        WorkerDeliveryGatewayClient requiredGateway =
                Objects.requireNonNull(gateway, "gateway");
        this.gateway = requiredGateway;
        commandLoop = new WorkerCommandLoop(
                requiredGateway,
                connections,
                resultQueue,
                adapterId,
                commandConsumeLimit,
                commandQueueCapacity
        );
        resultLoop = new WorkerResultLoop(
                requiredGateway,
                adapterId,
                resultQueue
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
            initializeScheduler();
            startListener();
            state = WorkerDeliveryAdapterState.RUNNING;
            commandTask = scheduler.scheduleWithFixedDelay(
                    this::runCommandLoopSafely,
                    0,
                    commandLoopInterval.toMillis(),
                    TimeUnit.MILLISECONDS
            );
            resultTask = scheduler.scheduleWithFixedDelay(
                    this::runResultLoopSafely,
                    resultSubmitInterval.toMillis(),
                    resultSubmitInterval.toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (RuntimeException error) {
            RuntimeException failure = classify(
                    error,
                    WorkerDeliveryAdapterErrorCode.LISTENER_START_FAILED,
                    "websocket.start",
                    "WebSocket Adapter could not start"
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
        ScheduledFuture<?> stoppingCommandTask;
        ScheduledFuture<?> stoppingResultTask;
        Channel stoppingListener;
        EventLoopGroup stoppingEventLoopGroup;
        synchronized (this) {
            if (state == WorkerDeliveryAdapterState.CLOSED) {
                return;
            }
            state = WorkerDeliveryAdapterState.STOPPING;
            stoppingScheduler = scheduler;
            stoppingCommandTask = commandTask;
            stoppingResultTask = resultTask;
            stoppingListener = listener;
            stoppingEventLoopGroup = eventLoopGroup;
            scheduler = null;
            commandTask = null;
            resultTask = null;
            listener = null;
            eventLoopGroup = null;
        }

        boolean interruptedOnEntry = Thread.interrupted();
        RuntimeException failure = interruptedOnEntry
                ? new WorkerDeliveryAdapterException(
                        WorkerDeliveryAdapterErrorCode
                                .SHUTDOWN_INTERRUPTED,
                        "websocket.stopScheduler",
                        "Adapter shutdown was already interrupted",
                        null
                )
                : null;
        cancel(stoppingCommandTask);
        commandLoop.close();
        failure = closeListener(stoppingListener, failure);
        try {
            connections.closeAll(
                    WorkerConnectionRegistry.ConnectionCloseReason
                            .ADAPTER_STOPPING
            );
        } catch (RuntimeException error) {
            failure = accumulate(failure, error);
        }
        cancel(stoppingResultTask);
        failure = stopScheduler(stoppingScheduler, failure);
        try {
            resultLoop.stopAccepting();
            resultLoop.closeAndFlush();
        } catch (RuntimeException error) {
            failure = accumulate(failure, error);
        }
        failure = stopEventLoop(stoppingEventLoopGroup, failure);

        synchronized (this) {
            state = WorkerDeliveryAdapterState.CLOSED;
        }
        if (interruptedOnEntry) {
            Thread.currentThread().interrupt();
        }
        if (failure != null) {
            throw classify(
                    failure,
                    WorkerDeliveryAdapterErrorCode.SHUTDOWN_INTERRUPTED,
                    "websocket.close",
                    "WebSocket Adapter could not close cleanly"
            );
        }
    }

    private void initializeScheduler() {
        scheduler = Executors.newScheduledThreadPool(
                2,
                daemonThreadFactory(adapterId + "-loop")
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
                                .addLast(new HttpServerCodec())
                                .addLast(new WriteTimeoutHandler(
                                        sendTimeLimit.toMillis(),
                                        TimeUnit.MILLISECONDS
                                ))
                                .addLast(new HttpObjectAggregator(
                                        MAX_HTTP_CONTENT_BYTES
                                ))
                                .addLast(
                                        new WebSocketServerProtocolHandler(
                                                WorkerWebSocketHandler
                                                        .WORKER_PATH,
                                                null,
                                                false,
                                                MAX_HTTP_CONTENT_BYTES,
                                                false,
                                                false
                                        )
                                )
                                .addLast(
                                        new UnmatchedWebSocketRequestHandler()
                                )
                                .addLast(new WorkerWebSocketHandler(
                                        connections,
                                        codec,
                                        resultQueue,
                                        gateway,
                                        adapterId,
                                        sendTimeLimit,
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

    private void runCommandLoopSafely() {
        if (state != WorkerDeliveryAdapterState.RUNNING) {
            return;
        }
        try {
            commandLoop.run();
        } catch (RuntimeException error) {
            WorkerDeliveryAdapterException failure = classify(
                    error,
                    WorkerDeliveryAdapterErrorCode.DELIVERY_INTERRUPTED,
                    "websocket.commandLoop",
                    "WebSocket Adapter command loop failed"
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

    private void runResultLoopSafely() {
        if (state != WorkerDeliveryAdapterState.RUNNING) {
            return;
        }
        try {
            resultLoop.run();
        } catch (RuntimeException error) {
            WorkerDeliveryAdapterException failure = classify(
                    error,
                    WorkerDeliveryAdapterErrorCode.DELIVERY_INTERRUPTED,
                    "websocket.resultLoop",
                    "WebSocket Adapter result loop failed"
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

    int activeConnectionCount() {
        return connections.activeConnectionCount();
    }

    private static void cancel(ScheduledFuture<?> task) {
        if (task != null) {
            task.cancel(true);
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
                            "websocket.stopScheduler",
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
