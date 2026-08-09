package com.xa.mass.integration.androidworker;

import com.xa.mass.worker.runtime.WorkerExecutionResources;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

final class AndroidWorkerExecutionResources implements AutoCloseable {

    private final ExecutorService controlExecutor;
    private final ExecutorService handlerExecutor;
    private final ScheduledExecutorService retryScheduler;
    private final WorkerExecutionResources resources;
    private boolean closed;

    AndroidWorkerExecutionResources() {
        controlExecutor = Executors.newSingleThreadExecutor(
                daemonThreadFactory("xa-android-worker-control")
        );
        handlerExecutor = Executors.newSingleThreadExecutor(
                daemonThreadFactory("xa-android-worker-handler")
        );
        retryScheduler = Executors.newSingleThreadScheduledExecutor(
                daemonThreadFactory("xa-android-worker-retry")
        );
        resources = WorkerExecutionResources.of(
                controlExecutor,
                handlerExecutor,
                retryScheduler
        );
    }

    WorkerExecutionResources resources() {
        return resources;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        retryScheduler.shutdownNow();
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
