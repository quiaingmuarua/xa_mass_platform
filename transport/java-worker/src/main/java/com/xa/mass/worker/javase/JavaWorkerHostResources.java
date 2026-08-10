package com.xa.mass.worker.javase;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.transport.client.TextMessageClientFactory;
import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import com.xa.mass.transport.client.WorkerControlClient;
import com.xa.mass.transport.client.WorkerTransportType;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

/**
 * Process-scoped Java Host owner for Worker network and execution resources.
 */
public final class JavaWorkerHostResources implements AutoCloseable {

    private final OkHttpClient httpClient;
    private final ExecutorService controlExecutor;
    private final ExecutorService commandExecutor;
    private final ScheduledExecutorService networkScheduler;
    private final ExecutorService socketExecutor;
    private boolean closed;

    private JavaWorkerHostResources(
            OkHttpClient httpClient,
            ExecutorService controlExecutor,
            ExecutorService commandExecutor,
            ScheduledExecutorService networkScheduler,
            ExecutorService socketExecutor
    ) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.controlExecutor = Objects.requireNonNull(
                controlExecutor,
                "controlExecutor"
        );
        this.commandExecutor = Objects.requireNonNull(
                commandExecutor,
                "commandExecutor"
        );
        this.networkScheduler = Objects.requireNonNull(
                networkScheduler,
                "networkScheduler"
        );
        this.socketExecutor = Objects.requireNonNull(
                socketExecutor,
                "socketExecutor"
        );
    }

    JavaWorkerHostResources(
            ExecutorService controlExecutor,
            ExecutorService commandExecutor
    ) {
        this(
                new OkHttpClient(),
                controlExecutor,
                commandExecutor,
                Executors.newSingleThreadScheduledExecutor(),
                Executors.newSingleThreadExecutor()
        );
    }

    public static JavaWorkerHostResources create(
            int workerCapacity,
            int maxConcurrentCommands,
            String threadNamePrefix,
            boolean daemonThreads
    ) {
        requirePositive(workerCapacity, "workerCapacity");
        requirePositive(
                maxConcurrentCommands,
                "maxConcurrentCommands"
        );
        String prefix = requireNonBlank(
                threadNamePrefix,
                "threadNamePrefix"
        ).trim();
        int controlThreads = Math.min(workerCapacity, 4);

        ExecutorService control = null;
        ThreadPoolExecutor commands = null;
        ScheduledExecutorService network = null;
        ExecutorService sockets = null;
        OkHttpClient http = null;
        try {
            ThreadFactory controlFactory = namedThreadFactory(
                    prefix + "-control",
                    daemonThreads
            );
            ThreadFactory commandFactory = namedThreadFactory(
                    prefix + "-command",
                    daemonThreads
            );
            ThreadFactory networkFactory = namedThreadFactory(
                    prefix + "-network",
                    daemonThreads
            );
            ThreadFactory socketFactory = namedThreadFactory(
                    prefix + "-socket",
                    daemonThreads
            );
            control = Executors.newFixedThreadPool(
                    controlThreads,
                    controlFactory
            );
            commands = new ThreadPoolExecutor(
                    maxConcurrentCommands,
                    maxConcurrentCommands,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new SynchronousQueue<>(),
                    commandFactory,
                    new ThreadPoolExecutor.AbortPolicy()
            );
            network = Executors.newSingleThreadScheduledExecutor(
                    networkFactory
            );
            sockets = Executors.newFixedThreadPool(
                    workerCapacity,
                    socketFactory
            );
            Dispatcher dispatcher = new Dispatcher();
            http = new OkHttpClient.Builder()
                    .dispatcher(dispatcher)
                    .build();
            return new JavaWorkerHostResources(
                    http,
                    control,
                    commands,
                    network,
                    sockets
            );
        } catch (RuntimeException | Error failure) {
            closeHttp(http);
            shutdown(sockets);
            shutdown(network);
            shutdown(commands);
            shutdown(control);
            throw failure;
        }
    }

    public synchronized Executor controlExecutor() {
        requireOpen();
        return controlExecutor;
    }

    public synchronized Executor commandExecutor() {
        requireOpen();
        return commandExecutor;
    }

    synchronized WorkerControlClient controlClient(URI runtimeApiBaseUrl) {
        requireOpen();
        return new JavaOkHttpWorkerControlClient(
                httpClient,
                runtimeApiBaseUrl
        );
    }

    synchronized TextMessageClient textClient(
            WorkerTransportType transportType,
            URI endpointUri,
            Duration requestTimeout,
            TextMessageReconnectPolicy reconnectPolicy
    ) {
        requireOpen();
        if (transportType == WorkerTransportType.WEBSOCKET) {
            long timeoutMillis = requirePositive(
                    requestTimeout,
                    "requestTimeout"
            ).toMillis();
            OkHttpClient socketHttp = httpClient.newBuilder()
                    .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                    .callTimeout(0, TimeUnit.MILLISECONDS)
                    .build();
            return new JavaOkHttpTextWebSocketClient(
                    socketHttp,
                    networkScheduler,
                    endpointUri,
                    reconnectPolicy
            );
        }
        if (transportType == WorkerTransportType.SOCKET) {
            return new JavaLineSocketClient(
                    socketExecutor,
                    endpointUri,
                    requestTimeout,
                    reconnectPolicy
            );
        }
        throw new IllegalArgumentException(
                "transportType must be WEBSOCKET or SOCKET"
        );
    }

    public synchronized TextMessageClientFactory textClientFactory(
            WorkerTransportType transportType,
            Duration requestTimeout,
            TextMessageReconnectPolicy reconnectPolicy
    ) {
        requireOpen();
        Objects.requireNonNull(transportType, "transportType");
        requirePositive(requestTimeout, "requestTimeout");
        Objects.requireNonNull(reconnectPolicy, "reconnectPolicy");
        if (transportType == WorkerTransportType.POLLING) {
            throw new IllegalArgumentException(
                    "transportType must be WEBSOCKET or SOCKET"
            );
        }
        return endpointUri -> textClient(
                transportType,
                endpointUri,
                requestTimeout,
                reconnectPolicy
        );
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        commandExecutor.shutdownNow();
        socketExecutor.shutdownNow();
        networkScheduler.shutdownNow();
        controlExecutor.shutdownNow();
        closeHttp(httpClient);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "JavaWorkerHostResources is closed"
            );
        }
    }

    private static ThreadFactory namedThreadFactory(
            String prefix,
            boolean daemon
    ) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(
                    runnable,
                    prefix + "-" + sequence.incrementAndGet()
            );
            thread.setDaemon(daemon);
            return thread;
        };
    }

    private static void closeHttp(OkHttpClient client) {
        if (client == null) {
            return;
        }
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdownNow();
    }

    private static void shutdown(ExecutorService executor) {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null
                || value.isZero()
                || value.isNegative()
                || value.toMillis() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value;
    }
}
