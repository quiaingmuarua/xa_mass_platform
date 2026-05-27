package com.xa.mass.runtime.worker;

/**
 * Candidate-source surface for worker matching.
 */
public interface WorkerCandidateRuntime {

    WorkerCandidateBatch<WorkerCandidateRow> findWorkerCandidateBatch(WorkerTaskSelector selector,
                                                                      int maxCandidateCount);
}
