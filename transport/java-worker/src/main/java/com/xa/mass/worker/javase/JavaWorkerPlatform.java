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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.internal.concurrent.TaskRunner;

/** Owns network and control resources for one Java Worker assembly. */
final class JavaWorkerPlatform implements AutoCloseable {

    private static final long HTTP_SHUTDOWN_TIMEOUT_MILLIS = 5_000;

    private final OkHttpClient httpClient;
    private final ExecutorService controlExecutor;
    private final ScheduledExecutorService networkScheduler;
    private final ExecutorService socketExecutor;
    private final TaskRunner.RealBackend httpTaskBackend;
    private final AtomicBoolean closed = new AtomicBoolean();

    private JavaWorkerPlatform(
            OkHttpClient httpClient,
            ExecutorService controlExecutor,
            ScheduledExecutorService networkScheduler,
            ExecutorService socketExecutor,
            TaskRunner.RealBackend httpTaskBackend
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
        this.httpTaskBackend = Objects.requireNonNull(
                httpTaskBackend,
                "httpTaskBackend"
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
        ExecutorService webSockets = null;
        TaskRunner taskRunner = null;
        TaskRunner.RealBackend httpTasks = null;
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
            webSockets = Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual()
                            .name(prefix + "-websocket-", 1)
                            .factory()
            );
            Dispatcher dispatcher = new Dispatcher(webSockets);
            dispatcher.setMaxRequests(workerCapacity);
            // WebSocket writer/close work uses TaskRunner, not Dispatcher.
            httpTasks = new TaskRunner.RealBackend(
                    Thread.ofVirtual()
                            .name(prefix + "-okhttp-task-", 1)
                            .factory()
            );
            taskRunner = new TaskRunner(
                    httpTasks,
                    TaskRunner.Companion.getLogger()
            );
            OkHttpClient.Builder httpBuilder = new OkHttpClient.Builder()
                    .dispatcher(dispatcher)
                    .taskRunner$okhttp(taskRunner);
            http = httpBuilder.build();
            return new JavaWorkerPlatform(
                    http,
                    control,
                    network,
                    sockets,
                    httpTasks
            );
        } catch (RuntimeException | Error failure) {
            closeHttp(http);
            closeTaskBackend(
                    http == null ? taskRunner
                            : http.getTaskRunner$okhttp(),
                    httpTasks
            );
            if (http == null) {
                shutdown(webSockets);
            }
            shutdown(sockets);
            shutdown(network);
            shutdown(control);
            throw failure;
        }
    }

    Executor controlExecutor() {
        requireOpen();
        return controlExecutor;
    }

    WorkerControlClient controlClient(
            URI runtimeApiBaseUrl
    ) {
        return workerControlClient(runtimeApiBaseUrl);
    }

    JavaOkHttpWorkerControlClient batchControlClient(
            URI runtimeApiBaseUrl
    ) {
        return workerControlClient(runtimeApiBaseUrl);
    }

    private JavaOkHttpWorkerControlClient workerControlClient(
            URI runtimeApiBaseUrl
    ) {
        requireOpen();
        return new JavaOkHttpWorkerControlClient(
                httpClient,
                runtimeApiBaseUrl
        );
    }

    TextMessageClient textClient(
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
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        socketExecutor.shutdownNow();
        networkScheduler.shutdownNow();
        controlExecutor.shutdownNow();
        closeHttp(httpClient);
        closeTaskBackend(
                httpClient.getTaskRunner$okhttp(),
                httpTaskBackend
        );
    }

    private void requireOpen() {
        if (closed.get()) {
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
        ExecutorService executor = client.dispatcher().executorService();
        executor.shutdownNow();
        awaitTermination(executor);
    }

    private static void shutdown(ExecutorService executor) {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private static void closeTaskBackend(
            TaskRunner taskRunner,
            TaskRunner.RealBackend backend
    ) {
        if (backend == null) {
            return;
        }
        if (taskRunner != null) {
            synchronized (taskRunner) {
                taskRunner.cancelAll();
            }
            long deadline = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(
                            HTTP_SHUTDOWN_TIMEOUT_MILLIS
                    );
            while (!taskRunner.activeQueues().isEmpty()
                    && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        backend.shutdown();
        awaitTermination(backend.getExecutor());
    }

    private static void awaitTermination(ExecutorService executor) {
        try {
            executor.awaitTermination(
                    HTTP_SHUTDOWN_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS
            );
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
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
