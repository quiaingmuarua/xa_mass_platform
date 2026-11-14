package com.xa.mass.worker.runtime;

/**
 * Performs one repeatable Worker preparation attempt.
 */
public interface WorkerPreparation extends AutoCloseable {

    WorkerPreparationResult prepare() throws Exception;

    @Override
    void close();
}
