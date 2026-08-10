package com.xa.mass.worker.runtime;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class TestHandlerExecutor implements AutoCloseable {

    private final ExecutorService executor =
            Executors.newFixedThreadPool(2);

    ExecutorService executor() {
        return executor;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
