package com.xa.mass.base.runtime;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Runtime execution boundary for transport, event, and polling work.
 *
 * <p>Engine lifecycle correctness must not depend on this abstraction. Use it
 * for runtime-owned asynchronous or blocking work that can be isolated from the
 * synchronous task state machine.
 */
public interface RuntimeTaskExecutor extends AutoCloseable {
    Future<?> submit(Runnable task);

    <T> Future<T> submit(Callable<T> task);

    void shutdown();

    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException;

    RuntimeTaskExecutorStatistics getStatistics();

    @Override
    default void close() {
        shutdown();
    }
}
