package com.xa.mass.integration.androidworker;

import com.xa.mass.worker.runtime.WorkerExecutionResources;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

final class AndroidWorkerExecutionResources implements AutoCloseable {

    private final ExecutorService controlExecutor;
    private final ExecutorService handlerExecutor;
    private final WorkerExecutionResources resources;
    private boolean closed;

    AndroidWorkerExecutionResources() {
        controlExecutor = Executors.newSingleThreadExecutor(
                daemonThreadFactory("xa-android-worker-control")
        );
        handlerExecutor = Executors.newSingleThreadExecutor(
                daemonThreadFactory("xa-android-worker-handler")
        );
        resources = WorkerExecutionResources.of(
                controlExecutor,
                handlerExecutor
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
