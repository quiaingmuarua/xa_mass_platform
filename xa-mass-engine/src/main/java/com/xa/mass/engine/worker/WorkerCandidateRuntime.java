package com.xa.mass.engine.worker;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;

import java.util.List;

/**
 * Candidate-source surface for worker matching.
 */
public interface WorkerCandidateRuntime {

    List<Worker> findWorkerCandidates(Task task);

    List<Worker> findWorkerCandidates(Task task, int maxCandidateCount);

    WorkerCandidateBatch findWorkerCandidateBatch(Task task, int maxCandidateCount);

    WorkerCandidateIndex getWorkerCandidateIndex();

    void recordWarmCandidate(Task task, Worker worker);
}
