package com.xa.mass.worker.runtime;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class TestWorkerExecutionResources implements AutoCloseable {

    private final ExecutorService controlExecutor =
            Executors.newFixedThreadPool(2);
    private final ExecutorService handlerExecutor =
            Executors.newFixedThreadPool(2);
    private final WorkerExecutionResources resources =
            WorkerExecutionResources.of(
                    controlExecutor,
                    handlerExecutor
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

    @Override
    public void close() {
        handlerExecutor.shutdownNow();
        controlExecutor.shutdownNow();
    }
}
