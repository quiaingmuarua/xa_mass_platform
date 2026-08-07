package com.xa.mass.worker.runtime;

/**
 * Performs one repeatable Worker preparation attempt.
 */
public interface WorkerPreparation extends AutoCloseable {

    PreparedWorker prepare() throws Exception;

    @Override
    void close();
}
