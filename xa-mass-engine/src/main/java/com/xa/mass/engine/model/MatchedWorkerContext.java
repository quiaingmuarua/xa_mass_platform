package com.xa.mass.engine.model;

import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;

import java.util.Objects;

/**
 * Concrete dispatch candidate chosen by the matching layer.
 */
public final class MatchedWorkerContext {

    private final Worker worker;
    private final WorkerContext workerContext;

    public MatchedWorkerContext(Worker worker, WorkerContext workerContext) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.workerContext = workerContext;
    }

    public Worker getWorker() {
        return worker;
    }

    public WorkerContext getWorkerContext() {
        return workerContext;
    }

    public String getWorkerId() {
        return worker.getWorkerId();
    }

    public String getWorkerContextId() {
        return workerContext != null ? workerContext.getWorkerContextId() : null;
    }
}
