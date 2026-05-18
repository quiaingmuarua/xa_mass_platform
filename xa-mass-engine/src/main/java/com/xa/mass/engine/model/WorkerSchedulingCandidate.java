package com.xa.mass.engine.model;

import com.xa.mass.base.model.Worker;

import java.util.Objects;

/**
 * Scheduling candidate chosen by the matching layer.
 *
 * <p>The candidate handoff is worker-level and carries no account-slot
 * identity.</p>
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

    public WorkerSchedulingView getSchedulingView() {
        return schedulingView;
    }
}
