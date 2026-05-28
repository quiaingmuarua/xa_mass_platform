package com.xa.mass.worker.runtime.admission;

import com.xa.mass.worker.runtime.candidate.WorkerCandidateRow;
import com.xa.mass.worker.runtime.candidate.WorkerTaskSelector;

/**
 * Runtime-owned task-local warm candidate hint mutation surface.
 */
public interface WorkerWarmHintRuntime {

    void recordWarmCandidate(WorkerTaskSelector selector, WorkerCandidateRow candidate);
}
