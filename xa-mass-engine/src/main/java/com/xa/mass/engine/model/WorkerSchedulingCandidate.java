package com.xa.mass.engine.model;

import com.xa.mass.base.model.Worker;

import java.util.Objects;

/**
 * Scheduling candidate chosen by the matching layer.
 *
 * <p>The candidate handoff is worker-level. Legacy context identity can still
 * appear on {@link WorkerSchedulingView} while compatibility evidence is being
 * retired, but the handoff no longer carries a WorkerContext payload.</p>
 */
public final class WorkerSchedulingCandidate {

    private final Worker worker;
    private final WorkerSchedulingView schedulingView;

    public WorkerSchedulingCandidate(Worker worker,
                                     WorkerSchedulingView schedulingView) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.schedulingView = Objects.requireNonNull(schedulingView, "schedulingView");
    }

    public Worker getWorker() {
        return worker;
    }

    public String getWorkerId() {
        return worker.getWorkerId();
    }

    public String getWorkerContextId() {
        return schedulingView.workerContextId();
    }

    public WorkerSchedulingView getSchedulingView() {
        return schedulingView;
    }
}
