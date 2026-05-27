package com.xa.mass.runtime.worker;

/**
 * Runtime-owned task-local warm candidate hint mutation surface.
 */
public interface WorkerWarmHintRuntime {

    void recordWarmCandidate(WorkerTaskSelector selector, WorkerCandidateRow candidate);
}
