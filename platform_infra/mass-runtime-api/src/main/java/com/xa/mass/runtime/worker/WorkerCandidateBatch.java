package com.xa.mass.runtime.worker;

import java.util.List;

/**
 * Bounded candidate-source batch with warm/cold diagnostic counts.
 */
public record WorkerCandidateBatch<T>(List<T> candidates,
                                      int warmCandidateCount,
                                      int coldCandidateCount,
                                      int warmSourceGuardRejectedCount) {

    public WorkerCandidateBatch {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        warmCandidateCount = Math.max(0, warmCandidateCount);
        coldCandidateCount = Math.max(0, coldCandidateCount);
        warmSourceGuardRejectedCount = Math.max(0, warmSourceGuardRejectedCount);
    }

    public static <T> WorkerCandidateBatch<T> empty() {
        return new WorkerCandidateBatch<>(List.of(), 0, 0, 0);
    }
}
