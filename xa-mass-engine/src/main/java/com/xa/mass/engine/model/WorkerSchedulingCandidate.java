package com.xa.mass.engine.model;

import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;

import java.util.Objects;

/**
 * Scheduling candidate chosen by the matching layer.
 *
 * <p>The worker context remains as the legacy runtime resource payload for
 * current binding, attempt, release, and trace behavior. Matching code should
 * read scheduling data from {@link WorkerSchedulingView}.</p>
 */
public final class WorkerSchedulingCandidate {

    private final Worker worker;
    private final WorkerContext workerContext;
    private final WorkerSchedulingView schedulingView;

    public WorkerSchedulingCandidate(Worker worker,
                                     WorkerContext workerContext,
                                     WorkerSchedulingView schedulingView) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.workerContext = workerContext;
        this.schedulingView = Objects.requireNonNull(schedulingView, "schedulingView");
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

    public WorkerSchedulingView getSchedulingView() {
        return schedulingView;
    }
}
