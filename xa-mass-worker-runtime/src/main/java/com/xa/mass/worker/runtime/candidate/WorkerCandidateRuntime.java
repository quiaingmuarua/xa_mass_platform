package com.xa.mass.worker.runtime.candidate;

/**
 * Candidate-source surface for worker matching.
 */
public interface WorkerCandidateRuntime {

    WorkerCandidateBatch<WorkerCandidateRow> findWorkerCandidateBatch(WorkerTaskSelector selector,
                                                                      int maxCandidateCount);
}
