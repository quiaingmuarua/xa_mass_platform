package com.xa.mass.worker.runtime;

import java.util.Objects;
import java.util.concurrent.ExecutorService;

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

    private WorkerExecutionResources(
            ExecutorService controlExecutor,
            ExecutorService handlerExecutor
    ) {
        this.controlExecutor = Objects.requireNonNull(
                controlExecutor,
                "controlExecutor"
        );
        this.handlerExecutor = Objects.requireNonNull(
                handlerExecutor,
                "handlerExecutor"
        );
    }

    public static WorkerExecutionResources of(
            ExecutorService controlExecutor,
            ExecutorService handlerExecutor
    ) {
        return new WorkerExecutionResources(
                controlExecutor,
                handlerExecutor
        );
    }

    ExecutorService controlExecutor() {
        return controlExecutor;
    }

    ExecutorService handlerExecutor() {
        return handlerExecutor;
    }
}
