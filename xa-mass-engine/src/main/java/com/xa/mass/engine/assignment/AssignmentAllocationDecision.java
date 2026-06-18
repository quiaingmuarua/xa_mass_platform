package com.xa.mass.engine.assignment;

import com.xa.mass.worker.runtime.selection.SelectedWorkerHandle;

import java.util.List;

public record AssignmentAllocationDecision(
        AssignmentAllocationOutcome outcome,
        List<SelectedWorkerHandle> dispatchCandidates,
        String reason
) {

    public AssignmentAllocationDecision {
        dispatchCandidates = dispatchCandidates == null ? List.of() : List.copyOf(dispatchCandidates);
    }
}
