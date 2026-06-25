package com.xa.mass.worker.runtime.candidate;

import java.util.List;

/**
 * Candidate-source surface for worker matching.
 */
public interface WorkerCandidateRuntime {

    List<WorkerCandidateRow> findWorkerCandidates(WorkerTaskSelector selector,
                                                  int maxCandidateCount);
}
