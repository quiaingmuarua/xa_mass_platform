package com.xa.mass.engine.worker;

import com.xa.mass.base.model.Worker;
import com.xa.mass.runtime.worker.WorkerCandidateBatch;
import com.xa.mass.runtime.worker.WorkerTaskSelector;

/**
 * Candidate-source surface for worker matching.
 */
public interface WorkerCandidateRuntime {

    WorkerCandidateBatch<Worker> findWorkerCandidateBatch(WorkerTaskSelector selector, int maxCandidateCount);

    void recordWarmCandidate(WorkerTaskSelector selector, Worker worker);
}
