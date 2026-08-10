package com.xa.mass.integration.androidworker;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

final class AndroidWorkerDemoResources implements AutoCloseable {

    private final ExecutorService controlExecutor;
    private final ExecutorService handlerExecutor;
    private boolean closed;

    AndroidWorkerDemoResources() {
        this(
                Executors.newSingleThreadExecutor(
                        daemonThreadFactory("xa-android-worker-control")
                ),
                Executors.newSingleThreadExecutor(
                        daemonThreadFactory("xa-android-worker-handler")
                )
        );
    }

    AndroidWorkerDemoResources(
            ExecutorService controlExecutor,
            ExecutorService handlerExecutor
    ) {
        this.controlExecutor = Objects.requireNonNull(
                controlExecutor,
                "controlExecutor"
        );
        this.handlerExecutor = Objects.requireNonNull(
                handlerExecutor,
                "handlerExecutor"
        );
    }

    Executor controlExecutor() {
        return controlExecutor;
    }

    Executor handlerExecutor() {
        return handlerExecutor;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        handlerExecutor.shutdownNow();
        controlExecutor.shutdownNow();
    }

    private static ThreadFactory daemonThreadFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
