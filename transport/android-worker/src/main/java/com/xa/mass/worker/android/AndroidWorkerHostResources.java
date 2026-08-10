package com.xa.mass.worker.android;

import android.os.HandlerThread;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import com.xa.mass.transport.client.WorkerControlClient;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

/**
 * Application-scoped Android owner for Worker network and execution resources.
 */
public final class AndroidWorkerHostResources implements AutoCloseable {

    private final OkHttpClient httpClient;
    private final HandlerThread networkThread;
    private final ExecutorService controlExecutor;
    private final ExecutorService commandExecutor;
    private boolean closed;

    private AndroidWorkerHostResources(
            OkHttpClient httpClient,
            HandlerThread networkThread,
            ExecutorService controlExecutor,
            ExecutorService commandExecutor
    ) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.networkThread = Objects.requireNonNull(
                networkThread,
                "networkThread"
        );
        this.controlExecutor = Objects.requireNonNull(
                controlExecutor,
                "controlExecutor"
        );
        this.commandExecutor = Objects.requireNonNull(
                commandExecutor,
                "commandExecutor"
        );
    }

    public static AndroidWorkerHostResources create(
            int workerCapacity,
            int maxConcurrentCommands,
            String threadNamePrefix
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

        HandlerThread network = null;
        ExecutorService control = null;
        ExecutorService commands = null;
        OkHttpClient http = null;
        try {
            network = new HandlerThread(prefix + "-network");
            network.start();
            control = Executors.newFixedThreadPool(
                    controlThreads,
                    namedThreadFactory(prefix + "-control")
            );
            commands = new ThreadPoolExecutor(
                    maxConcurrentCommands,
                    maxConcurrentCommands,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new SynchronousQueue<>(),
                    namedThreadFactory(prefix + "-command"),
                    new ThreadPoolExecutor.AbortPolicy()
            );
            Dispatcher dispatcher = new Dispatcher();
            http = new OkHttpClient.Builder()
                    .dispatcher(dispatcher)
                    .build();
            return new AndroidWorkerHostResources(
                    http,
                    network,
                    control,
                    commands
            );
        } catch (RuntimeException | Error failure) {
            closeHttp(http);
            shutdown(commands);
            shutdown(control);
            if (network != null) {
                network.quitSafely();
            }
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
        return new AndroidOkHttpWorkerControlClient(
                httpClient,
                runtimeApiBaseUrl
        );
    }

    synchronized TextMessageClient textClient(
            URI endpointUri,
            Duration requestTimeout,
            TextMessageReconnectPolicy reconnectPolicy
    ) {
        requireOpen();
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
        return new AndroidOkHttpTextWebSocketClient(
                socketHttp,
                networkThread.getLooper(),
                endpointUri,
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
        controlExecutor.shutdownNow();
        networkThread.quitSafely();
        closeHttp(httpClient);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "AndroidWorkerHostResources is closed"
            );
        }
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> new Thread(
                runnable,
                prefix + "-" + sequence.incrementAndGet()
        );
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
