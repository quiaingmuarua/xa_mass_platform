package com.xa.mass.engine.model;

import com.xa.mass.runtime.worker.WorkerCandidateRow;

import java.util.Objects;

/**
 * Scheduling candidate chosen by the matching layer.
 *
 * <p>The candidate handoff is worker-level and carries no account-slot
 * identity.</p>
 */
public final class WorkerSchedulingCandidate {

    private final WorkerCandidateRow candidateRow;
    private final WorkerSchedulingView schedulingView;

    public WorkerSchedulingCandidate(WorkerCandidateRow candidateRow,
                                     WorkerSchedulingView schedulingView) {
        this.candidateRow = Objects.requireNonNull(candidateRow, "candidateRow");
        this.schedulingView = Objects.requireNonNull(schedulingView, "schedulingView");
    }

    public WorkerCandidateRow getCandidateRow() {
        return candidateRow;
    }

    public String getWorkerId() {
        return candidateRow.workerId();
    }

    public WorkerSchedulingView getSchedulingView() {
        return schedulingView;
    }
}
