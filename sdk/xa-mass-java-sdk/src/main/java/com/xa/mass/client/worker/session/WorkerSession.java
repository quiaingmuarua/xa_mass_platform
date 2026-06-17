package com.xa.mass.client.worker.session;

/**
 * Public Java SDK lifecycle contract for one managed external worker session.
 */
public interface WorkerSession extends AutoCloseable {
    String workerId();

    String workerGroupId();

    String transportHint();

    WorkerSession start();

    boolean isRunning();

    @Override
    void close();
}
