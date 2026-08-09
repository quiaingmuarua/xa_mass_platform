package com.xa.mass.worker.runtime;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Host-owned execution resources shared by one or more Workers.
 *
 * <p>Worker Core never creates or closes these resources. The host must keep
 * them available until every Worker using them has been closed, then shut the
 * underlying executors down itself.
 */
public final class WorkerExecutionResources {

    private final ExecutorService controlExecutor;
    private final ExecutorService handlerExecutor;
    private final ScheduledExecutorService retryScheduler;

    private WorkerExecutionResources(
            ExecutorService controlExecutor,
            ExecutorService handlerExecutor,
            ScheduledExecutorService retryScheduler
    ) {
        this.controlExecutor = Objects.requireNonNull(
                controlExecutor,
                "controlExecutor"
        );
        this.handlerExecutor = Objects.requireNonNull(
                handlerExecutor,
                "handlerExecutor"
        );
        this.retryScheduler = Objects.requireNonNull(
                retryScheduler,
                "retryScheduler"
        );
    }

    public static WorkerExecutionResources of(
            ExecutorService controlExecutor,
            ExecutorService handlerExecutor,
            ScheduledExecutorService retryScheduler
    ) {
        return new WorkerExecutionResources(
                controlExecutor,
                handlerExecutor,
                retryScheduler
        );
    }

    ExecutorService controlExecutor() {
        return controlExecutor;
    }

    ExecutorService handlerExecutor() {
        return handlerExecutor;
    }

    ScheduledExecutorService retryScheduler() {
        return retryScheduler;
    }
}
