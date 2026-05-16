package com.xa.mass.engine.strategy;

import com.xa.mass.base.model.Worker;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.WorkerReachabilityState;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;
import com.xa.mass.engine.model.WorkerSchedulingView;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Owner for turning worker read-model rows into scheduling candidates.
 */
public class WorkerSchedulingCandidateEnumerator {

    private final WorkerManager workerManager;

    public WorkerSchedulingCandidateEnumerator(WorkerManager workerManager) {
        this.workerManager = Objects.requireNonNull(workerManager, "workerManager");
    }

    public List<WorkerSchedulingCandidate> enumerate(List<Worker> workers) {
        if (workers == null || workers.isEmpty()) {
            return List.of();
        }
        List<WorkerSchedulingCandidate> candidates = new ArrayList<>();
        for (Worker worker : workers) {
            candidates.add(toSchedulingCandidate(worker));
        }
        return candidates;
    }

    private WorkerSchedulingCandidate toSchedulingCandidate(Worker worker) {
        WorkerReachabilityState reachability = workerManager.getWorkerReachability(worker.getWorkerId());
        boolean dispatchEnabled = workerManager.isWorkerDispatchEnabled(worker);
        boolean workerLocked = workerManager.isLocked(worker.getWorkerId());
        return new WorkerSchedulingCandidate(
                worker,
                WorkerSchedulingView.from(
                        worker,
                        reachability,
                        dispatchEnabled,
                        workerLocked,
                        workerManager.getWorkerLoad(worker.getWorkerId())
                )
        );
    }
}
