package com.xa.mass.worker.javase;

import com.xa.mass.transport.client.TextMessageClient;
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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

/** Owns network and control resources for one Java Worker assembly. */
final class JavaWorkerPlatform implements AutoCloseable {

    private final OkHttpClient httpClient;
    private final ExecutorService controlExecutor;
    private final ScheduledExecutorService networkScheduler;
    private final ExecutorService socketExecutor;
    private boolean closed;

    private JavaWorkerPlatform(
            OkHttpClient httpClient,
            ExecutorService controlExecutor,
            ScheduledExecutorService networkScheduler,
            ExecutorService socketExecutor
    ) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.controlExecutor = Objects.requireNonNull(
                controlExecutor,
                "controlExecutor"
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

    static JavaWorkerPlatform standalone(String workerGroupId) {
        return create(
                1,
                "xa-java-worker-" + threadSegment(workerGroupId),
                false
        );
    }

    static JavaWorkerPlatform managed(
            String workerGroupId,
            int replicaCount
    ) {
        return create(
                replicaCount,
                "xa-java-worker-manager-" + threadSegment(workerGroupId),
                true
        );
    }

    static JavaWorkerPlatform create(
            int workerCapacity,
            String threadNamePrefix,
            boolean daemonThreads
    ) {
        requirePositive(workerCapacity, "workerCapacity");
        String prefix = requireNonBlank(
                threadNamePrefix,
                "threadNamePrefix"
        ).trim();
        int controlThreads = Math.min(workerCapacity, 4);

        ExecutorService control = null;
        ScheduledExecutorService network = null;
        ExecutorService sockets = null;
        OkHttpClient http = null;
        try {
            control = Executors.newFixedThreadPool(
                    controlThreads,
                    namedThreadFactory(
                            prefix + "-control",
                            daemonThreads
                    )
            );
            network = Executors.newSingleThreadScheduledExecutor(
                    namedThreadFactory(
                            prefix + "-network",
                            daemonThreads
                    )
            );
            sockets = Executors.newFixedThreadPool(
                    workerCapacity,
                    namedThreadFactory(
                            prefix + "-socket",
                            daemonThreads
                    )
            );
            Dispatcher dispatcher = new Dispatcher();
            http = new OkHttpClient.Builder()
                    .dispatcher(dispatcher)
                    .build();
            return new JavaWorkerPlatform(
                    http,
                    control,
                    network,
                    sockets
            );
        } catch (RuntimeException | Error failure) {
            closeHttp(http);
            shutdown(sockets);
            shutdown(network);
            shutdown(control);
            throw failure;
        }
    }

    synchronized Executor controlExecutor() {
        requireOpen();
        return controlExecutor;
    }

    synchronized WorkerControlClient controlClient(
            URI runtimeApiBaseUrl
    ) {
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
        Objects.requireNonNull(transportType, "transportType");
        Objects.requireNonNull(endpointUri, "endpointUri");
        requirePositive(requestTimeout, "requestTimeout");
        Objects.requireNonNull(reconnectPolicy, "reconnectPolicy");
        if (transportType == WorkerTransportType.POLLING) {
            throw new IllegalArgumentException(
                    "transportType must be WEBSOCKET or SOCKET"
            );
        }
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

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        socketExecutor.shutdownNow();
        networkScheduler.shutdownNow();
        controlExecutor.shutdownNow();
        closeHttp(httpClient);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Java Worker platform is closed");
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

    private static String threadSegment(String value) {
        String source = requireNonBlank(value, "workerGroupId").trim();
        return source.replaceAll("[^A-Za-z0-9._-]", "-");
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
