package com.xa.mass.engine.model;

import com.xa.mass.worker.runtime.candidate.WorkerCandidateRow;

import java.util.Objects;

/**
 * Scheduling candidate chosen by the matching layer.
 *
 * <p>The candidate handoff carries worker identity plus WorkerGroup evidence.
 * Live reachability, dispatch-gate, load, and admission evidence stay on the
 * scheduling view and admission contracts.</p>
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
