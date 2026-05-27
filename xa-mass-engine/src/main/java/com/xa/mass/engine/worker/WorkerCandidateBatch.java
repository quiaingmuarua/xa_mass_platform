package com.xa.mass.engine.worker;

import com.xa.mass.base.model.Worker;

import java.util.List;

/**
 * Bounded candidate-source batch with warm/cold diagnostic counts.
 */
public record WorkerCandidateBatch(List<Worker> candidates,
                                   int warmCandidateCount,
                                   int coldCandidateCount,
                                   int warmSourceGuardRejectedCount) {

    public WorkerCandidateBatch {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        warmCandidateCount = Math.max(0, warmCandidateCount);
        coldCandidateCount = Math.max(0, coldCandidateCount);
        warmSourceGuardRejectedCount = Math.max(0, warmSourceGuardRejectedCount);
    }

    static WorkerCandidateBatch empty() {
        return new WorkerCandidateBatch(List.of(), 0, 0, 0);
    }
}
