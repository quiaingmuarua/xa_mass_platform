package com.xa.mass.client.worker.runtime;

/**
 * Public Java SDK lifecycle contract for one managed external worker runtime.
 */
public interface WorkerRuntime extends AutoCloseable {
    String workerId();

    String workerGroupId();

    String transportHint();

    WorkerRuntimeReporter reporter();

    WorkerRuntime start();

    boolean isRunning();

    @Override
    void close();
}
