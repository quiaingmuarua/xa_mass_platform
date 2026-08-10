package com.xa.mass.worker.javase;

import com.xa.mass.worker.runtime.WorkerExecutionResources;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-scoped Java Host owner for shared Worker execution resources.
 */
public final class JavaWorkerHostResources implements AutoCloseable {

    private final ExecutorService controlExecutor;
    private final ExecutorService handlerExecutor;
    private final WorkerExecutionResources executionResources;

    private boolean closed;

    private JavaWorkerHostResources(
            ExecutorService controlExecutor,
            ExecutorService handlerExecutor
    ) {
        this.controlExecutor = controlExecutor;
        this.handlerExecutor = handlerExecutor;
        executionResources = WorkerExecutionResources.of(
                controlExecutor,
                handlerExecutor
        );
    }

    public static JavaWorkerHostResources create(
            int totalReplicaCount,
            String threadNamePrefix,
            boolean daemonThreads
    ) {
        if (totalReplicaCount <= 0) {
            throw new IllegalArgumentException(
                    "totalReplicaCount must be positive"
            );
        }
        String prefix = requireNonBlank(
                threadNamePrefix,
                "threadNamePrefix"
        ).trim();
        int controlThreads = Math.max(
                1,
                Math.min(totalReplicaCount, 4)
        );
        int handlerThreads = Math.max(
                1,
                Math.min(
                        totalReplicaCount,
                        Math.max(
                                2,
                                Runtime.getRuntime().availableProcessors()
                        )
                )
        );

        ExecutorService control = null;
        ExecutorService handler = null;
        try {
            control = Executors.newFixedThreadPool(
                    controlThreads,
                    namedThreadFactory(
                            prefix + "-control",
                            daemonThreads
                    )
            );
            handler = Executors.newFixedThreadPool(
                    handlerThreads,
                    namedThreadFactory(
                            prefix + "-handler",
                            daemonThreads
                    )
            );
            return new JavaWorkerHostResources(
                    control,
                    handler
            );
        } catch (RuntimeException | Error failure) {
            shutdown(handler);
            shutdown(control);
            throw failure;
        }
    }

    public WorkerExecutionResources executionResources() {
        return executionResources;
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

    private static ThreadFactory namedThreadFactory(
            String prefix,
            boolean daemon
    ) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(
                    runnable,
                    prefix + "-" + sequence.incrementAndGet()
            );
            thread.setDaemon(daemon);
            return thread;
        };
    }

    private static void shutdown(ExecutorService executor) {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    name + " must be non-blank"
            );
        }
        return value;
    }
}
