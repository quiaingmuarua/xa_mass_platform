package com.xa.mass.engine.assignment;

import com.xa.mass.engine.model.WorkerSchedulingCandidate;

import java.util.List;

public record AssignmentAllocationDecision(
        AssignmentAllocationOutcome outcome,
        List<WorkerSchedulingCandidate> dispatchCandidates,
        String reason
) {

    public AssignmentAllocationDecision {
        dispatchCandidates = dispatchCandidates == null ? List.of() : List.copyOf(dispatchCandidates);
    }
}
