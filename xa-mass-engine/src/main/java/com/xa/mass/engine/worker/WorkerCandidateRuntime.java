package com.xa.mass.engine.worker;

import com.xa.mass.base.model.Worker;
import com.xa.mass.runtime.worker.WorkerTaskSelector;

import java.util.List;

/**
 * Candidate-source surface for worker matching.
 */
public interface WorkerCandidateRuntime {

    List<Worker> findWorkerCandidates(WorkerTaskSelector selector);

    List<Worker> findWorkerCandidates(WorkerTaskSelector selector, int maxCandidateCount);

    WorkerCandidateBatch findWorkerCandidateBatch(WorkerTaskSelector selector, int maxCandidateCount);

    WorkerCandidateIndex getWorkerCandidateIndex();

    void recordWarmCandidate(WorkerTaskSelector selector, Worker worker);
}
