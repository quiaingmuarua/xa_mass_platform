package com.xa.mass.engine.assignment;

import com.xa.mass.engine.model.MatchedWorkerContext;

import java.util.List;

public record AssignmentAllocationDecision(
        AssignmentAllocationOutcome outcome,
        List<MatchedWorkerContext> dispatchCandidates,
        String reason
) {

    public AssignmentAllocationDecision {
        dispatchCandidates = dispatchCandidates == null ? List.of() : List.copyOf(dispatchCandidates);
    }
}
