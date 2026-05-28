package com.xa.mass.worker.runtime.candidate;

import java.util.List;

/**
 * Bounded candidate-source batch with warm/cold diagnostic counts.
 */
public record WorkerCandidateBatch<T>(List<T> candidates,
                                      int warmCandidateCount,
                                      int coldCandidateCount,
                                      int warmSourceGuardRejectedCount,
                                      int duplicateCandidateCount) {

    public WorkerCandidateBatch {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        warmCandidateCount = Math.max(0, warmCandidateCount);
        coldCandidateCount = Math.max(0, coldCandidateCount);
        warmSourceGuardRejectedCount = Math.max(0, warmSourceGuardRejectedCount);
        duplicateCandidateCount = Math.max(0, duplicateCandidateCount);
    }

    public WorkerCandidateBatch(List<T> candidates,
                                int warmCandidateCount,
                                int coldCandidateCount,
                                int warmSourceGuardRejectedCount) {
        this(candidates, warmCandidateCount, coldCandidateCount, warmSourceGuardRejectedCount, 0);
    }

    public static <T> WorkerCandidateBatch<T> empty() {
        return new WorkerCandidateBatch<>(List.of(), 0, 0, 0, 0);
    }
}
