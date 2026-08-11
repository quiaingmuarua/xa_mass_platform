package com.xa.mass.workerdelivery.adapter.internal;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SYSTEM_POLLING_ENDPOINT_MANAGER_ID;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterState;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
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
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.timeout.WriteTimeoutHandler;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared Netty-specific runtime used only by the two concrete Adapter façades.
 * This public visibility is a Java package-boundary accommodation, not an
 * Adapter SPI.
 */
public final class NettyWorkerDeliveryAdapterRuntime implements AutoCloseable {

    private static final int MAX_FRAME_BYTES = 1_048_576;
    private static final String WORKER_WEBSOCKET_PATH =
            "/api/v1/worker-delivery/websocket";
    private static final System.Logger LOGGER = System.getLogger(
            NettyWorkerDeliveryAdapterRuntime.class.getName()
    );

    private final TransportKind transportKind;
    private final String adapterId;
    private final String listenHost;
    private final int listenPort;
    private final Duration commandPumpInterval;
    private final Duration reportSubmitInterval;
    private final Duration sendTimeLimit;
    private final Duration shutdownTimeout;
    private final TextFrameStrategy frameStrategy;
    private final BoundWorkerConnectionDirectory connections;
    private final DeliveryCommandPump commandPump;
    private final DeliveryReportPump reportPump;
    private final WorkerConnectionSessionFactory sessionFactory;
    private final Set<Channel> childChannels = ConcurrentHashMap.newKeySet();
    private volatile WorkerDeliveryAdapterState state =
            WorkerDeliveryAdapterState.REGISTERED;
    private EventLoopGroup eventLoopGroup;
    private Channel listener;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> commandTask;
    private ScheduledFuture<?> reportTask;

    public static NettyWorkerDeliveryAdapterRuntime webSocket(
            String adapterId,
            WorkerDeliveryGatewayClient gateway,
            String listenHost,
            int listenPort,
            Duration commandLoopInterval,
            int commandConsumeLimit,
            int commandQueueCapacity,
            Duration reportSubmitInterval,
            int reportQueueCapacity,
            Duration sendTimeLimit,
            Duration shutdownTimeout
    ) {
        return new NettyWorkerDeliveryAdapterRuntime(
                TransportKind.WEBSOCKET,
                adapterId,
                gateway,
                listenHost,
                listenPort,
                commandLoopInterval,
                commandConsumeLimit,
                commandQueueCapacity,
                reportSubmitInterval,
                reportQueueCapacity,
                sendTimeLimit,
                shutdownTimeout
        );
    }

    public static NettyWorkerDeliveryAdapterRuntime socket(
            String adapterId,
            WorkerDeliveryGatewayClient gateway,
            String listenHost,
            int listenPort,
            Duration commandLoopInterval,
            int commandConsumeLimit,
            int commandQueueCapacity,
            Duration reportSubmitInterval,
            int reportQueueCapacity,
            Duration sendTimeLimit,
            Duration shutdownTimeout
    ) {
        return new NettyWorkerDeliveryAdapterRuntime(
                TransportKind.SOCKET,
                adapterId,
                gateway,
                listenHost,
                listenPort,
                commandLoopInterval,
                commandConsumeLimit,
                commandQueueCapacity,
                reportSubmitInterval,
                reportQueueCapacity,
                sendTimeLimit,
                shutdownTimeout
        );
    }

    private NettyWorkerDeliveryAdapterRuntime(
            TransportKind transportKind,
            String adapterId,
            WorkerDeliveryGatewayClient gateway,
            String listenHost,
            int listenPort,
            Duration commandPumpInterval,
            int commandConsumeLimit,
            int commandQueueCapacity,
            Duration reportSubmitInterval,
            int reportQueueCapacity,
            Duration sendTimeLimit,
            Duration shutdownTimeout
    ) {
        this.transportKind = Objects.requireNonNull(
                transportKind,
                "transportKind"
        );
        this.adapterId = requireAdapterId(adapterId);
        if (listenHost == null || listenHost.isBlank()) {
            throw new IllegalArgumentException("listenHost must be non-blank");
        }
        if (listenPort < 1 || listenPort > 65_535) {
            throw new IllegalArgumentException(
                    "listenPort must be between 1 and 65535"
            );
        }
        requirePositive(commandPumpInterval, "commandLoopInterval");
        requirePositive(reportSubmitInterval, "resultSubmitInterval");
        requirePositive(sendTimeLimit, "sendTimeLimit");
        requirePositive(shutdownTimeout, "shutdownTimeout");
        if (commandConsumeLimit <= 0 || reportQueueCapacity <= 0) {
            throw new IllegalArgumentException(
                    "Adapter bounds must be positive"
            );
        }
        if (commandQueueCapacity < commandConsumeLimit) {
            throw new IllegalArgumentException(
                    "commandQueueCapacity must be at least commandConsumeLimit"
            );
        }

        WorkerDeliveryGatewayClient requiredGateway = Objects.requireNonNull(
                gateway,
                "gateway"
        );
        WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
        BoundedDeliveryReportQueue reportQueue =
                new BoundedDeliveryReportQueue(reportQueueCapacity);
        frameStrategy = transportKind == TransportKind.WEBSOCKET
                ? WebSocketTextFrameStrategy.INSTANCE
                : SocketLineFrameStrategy.INSTANCE;
        connections = new BoundWorkerConnectionDirectory(codec);
        commandPump = new DeliveryCommandPump(
                requiredGateway,
                connections,
                reportQueue,
                adapterId,
                commandConsumeLimit,
                commandQueueCapacity
        );
        reportPump = new DeliveryReportPump(
                requiredGateway,
                adapterId,
                reportQueue
        );
        sessionFactory = new WorkerConnectionSessionFactory(
                connections,
                codec,
                reportQueue,
                requiredGateway,
                adapterId,
                sendTimeLimit,
                this::acceptingConnections,
                frameStrategy,
                transportKind.operationPrefix
        );
        this.listenHost = listenHost;
        this.listenPort = listenPort;
        this.commandPumpInterval = commandPumpInterval;
        this.reportSubmitInterval = reportSubmitInterval;
        this.sendTimeLimit = sendTimeLimit;
        this.shutdownTimeout = shutdownTimeout;
    }

    public String adapterId() {
        return adapterId;
    }

    public WorkerDeliveryAdapterState state() {
        return state;
    }

    public String listenHost() {
        return listenHost;
    }

    public int listenPort() {
        return listenPort;
    }

    public int activeConnectionCount() {
        return connections.activeConnectionCount();
    }

    public int trackedConnectionCount() {
        return childChannels.size();
    }

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
                    this::runCommandPumpSafely,
                    0,
                    commandPumpInterval.toMillis(),
                    TimeUnit.MILLISECONDS
            );
            reportTask = scheduler.scheduleWithFixedDelay(
                    this::runReportPumpSafely,
                    reportSubmitInterval.toMillis(),
                    reportSubmitInterval.toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (RuntimeException error) {
            RuntimeException failure = classify(
                    error,
                    WorkerDeliveryAdapterErrorCode.LISTENER_START_FAILED,
                    transportKind.operationPrefix + ".start",
                    transportKind.displayName + " Adapter could not start"
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
        ScheduledFuture<?> stoppingReportTask;
        Channel stoppingListener;
        EventLoopGroup stoppingEventLoopGroup;
        synchronized (this) {
            if (state == WorkerDeliveryAdapterState.CLOSED) {
                return;
            }
            state = WorkerDeliveryAdapterState.STOPPING;
            stoppingScheduler = scheduler;
            stoppingCommandTask = commandTask;
            stoppingReportTask = reportTask;
            stoppingListener = listener;
            stoppingEventLoopGroup = eventLoopGroup;
            scheduler = null;
            commandTask = null;
            reportTask = null;
            listener = null;
            eventLoopGroup = null;
        }

        boolean interruptedOnEntry = Thread.interrupted();
        RuntimeException failure = interruptedOnEntry
                ? new WorkerDeliveryAdapterException(
                        WorkerDeliveryAdapterErrorCode.SHUTDOWN_INTERRUPTED,
                        transportKind.operationPrefix + ".stopScheduler",
                        "Adapter shutdown was already interrupted",
                        null
                )
                : null;
        cancel(stoppingCommandTask);
        commandPump.close();
        failure = closeListener(stoppingListener, failure);
        failure = closeChildChannels(failure);
        cancel(stoppingReportTask);
        failure = stopScheduler(stoppingScheduler, failure);
        try {
            reportPump.stopAccepting();
            reportPump.closeAndFlush();
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
                    transportKind.operationPrefix + ".close",
                    transportKind.displayName
                            + " Adapter could not close cleanly"
            );
        }
    }

    private boolean acceptingConnections() {
        return state == WorkerDeliveryAdapterState.RUNNING;
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
                        track(channel);
                        if (transportKind == TransportKind.WEBSOCKET) {
                            initializeWebSocketPipeline(channel);
                        } else {
                            initializeSocketPipeline(channel);
                        }
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
                    transportKind.operationPrefix + ".startListener",
                    transportKind.displayName
                            + " listener start was interrupted",
                    error
            );
        } catch (Exception error) {
            throw new WorkerDeliveryAdapterException(
                    WorkerDeliveryAdapterErrorCode.LISTENER_START_FAILED,
                    transportKind.operationPrefix + ".startListener",
                    "Could not bind " + transportKind.displayName
                            + " Adapter " + adapterId,
                    error
            );
        }
    }

    private void initializeWebSocketPipeline(SocketChannel channel) {
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
                .addLast(new UnmatchedWebSocketRequestHandler())
                .addLast(new WebSocketWorkerChannelHandler(
                        sessionFactory,
                        this::acceptingConnections
                ));
    }

    private void initializeSocketPipeline(SocketChannel channel) {
        channel.pipeline()
                .addLast(new LineBasedFrameDecoder(
                        MAX_FRAME_BYTES,
                        true,
                        true
                ))
                .addLast(new StringDecoder(StandardCharsets.UTF_8))
                .addLast(new StringEncoder(StandardCharsets.UTF_8))
                .addLast(new WriteTimeoutHandler(
                        sendTimeLimit.toMillis(),
                        TimeUnit.MILLISECONDS
                ))
                .addLast(new SocketWorkerChannelHandler(
                        sessionFactory,
                        this::acceptingConnections
                ));
    }

    private void track(Channel channel) {
        childChannels.add(channel);
        channel.closeFuture().addListener(ignored ->
                childChannels.remove(channel)
        );
    }

    private void runCommandPumpSafely() {
        if (!acceptingConnections()) {
            return;
        }
        try {
            commandPump.run();
        } catch (RuntimeException error) {
            logPumpFailure(error, "commandPump", "command pump");
        }
    }

    private void runReportPumpSafely() {
        if (!acceptingConnections()) {
            return;
        }
        try {
            reportPump.run();
        } catch (RuntimeException error) {
            logPumpFailure(error, "reportPump", "report pump");
        }
    }

    private void logPumpFailure(
            RuntimeException error,
            String action,
            String description
    ) {
        WorkerDeliveryAdapterException failure = classify(
                error,
                WorkerDeliveryAdapterErrorCode.DELIVERY_INTERRUPTED,
                transportKind.operationPrefix + "." + action,
                transportKind.displayName + " Adapter "
                        + description + " failed"
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

    private RuntimeException closeChildChannels(RuntimeException failure) {
        try {
            for (Channel channel : Set.copyOf(childChannels)) {
                frameStrategy.close(
                        channel,
                        ConnectionCloseReason.ADAPTER_STOPPING
                );
            }
            long deadline = System.nanoTime()
                    + shutdownTimeout.toNanos();
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
                            transportKind.operationPrefix
                                    + ".stopScheduler",
                            "Adapter scheduler shutdown was interrupted",
                            error
                    )
            );
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

    private static void cancel(ScheduledFuture<?> task) {
        if (task != null) {
            task.cancel(true);
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

    private static String requireAdapterId(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must be non-blank");
        }
        if (SYSTEM_POLLING_ENDPOINT_MANAGER_ID.equals(adapterId)) {
            throw new IllegalArgumentException(
                    "system-polling cannot own an active Adapter"
            );
        }
        return adapterId;
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null
                || value.isZero()
                || value.isNegative()
                || value.toMillis() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private enum TransportKind {
        WEBSOCKET("websocket", "WebSocket"),
        SOCKET("socket", "Socket");

        private final String operationPrefix;
        private final String displayName;

        TransportKind(String operationPrefix, String displayName) {
            this.operationPrefix = operationPrefix;
            this.displayName = displayName;
        }
    }
}
