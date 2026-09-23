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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

/** Owns network and control resources for one Android Worker. */
final class AndroidWorkerPlatform implements AutoCloseable {

    private final OkHttpClient httpClient;
    private final HandlerThread networkThread;
    private final ExecutorService controlExecutor;
    private boolean closed;

    private AndroidWorkerPlatform(
            OkHttpClient httpClient,
            HandlerThread networkThread,
            ExecutorService controlExecutor
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
    }

    static AndroidWorkerPlatform create(String workerGroupId) {
        String prefix = "xa-android-worker-" + threadSegment(workerGroupId);
        HandlerThread network = null;
        ExecutorService control = null;
        OkHttpClient http = null;
        try {
            network = new HandlerThread(prefix + "-network");
            network.start();
            control = Executors.newSingleThreadExecutor(
                    namedThreadFactory(prefix + "-control")
            );
            Dispatcher dispatcher = new Dispatcher();
            http = new OkHttpClient.Builder()
                    .dispatcher(dispatcher)
                    .build();
            return new AndroidWorkerPlatform(http, network, control);
        } catch (RuntimeException | Error failure) {
            closeHttp(http);
            shutdown(control);
            if (network != null) {
                network.quitSafely();
            }
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
        controlExecutor.shutdownNow();
        networkThread.quitSafely();
        closeHttp(httpClient);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "Android Worker platform is closed"
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

    private static String threadSegment(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "workerGroupId must be non-blank"
            );
        }
        return value.trim().replaceAll("[^A-Za-z0-9._-]", "-");
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

    private static Duration requirePositive(Duration value, String name) {
        if (value == null
                || value.isZero()
                || value.isNegative()
                || value.toMillis() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
