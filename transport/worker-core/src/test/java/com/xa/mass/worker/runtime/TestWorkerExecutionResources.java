package com.xa.mass.worker.runtime;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

final class TestWorkerExecutionResources implements AutoCloseable {

    private final ExecutorService controlExecutor =
            Executors.newFixedThreadPool(2);
    private final ExecutorService handlerExecutor =
            Executors.newFixedThreadPool(2);
    private final ScheduledExecutorService retryScheduler =
            Executors.newSingleThreadScheduledExecutor();
    private final WorkerExecutionResources resources =
            WorkerExecutionResources.of(
                    controlExecutor,
                    handlerExecutor,
                    retryScheduler
            );

    WorkerExecutionResources resources() {
        return resources;
    }

    ExecutorService handlerExecutor() {
        return handlerExecutor;
    }

    ExecutorService controlExecutor() {
        return controlExecutor;
    }

    ScheduledExecutorService retryScheduler() {
        return retryScheduler;
    }

    @Override
    public void close() {
        retryScheduler.shutdownNow();
        handlerExecutor.shutdownNow();
        controlExecutor.shutdownNow();
    }
}
