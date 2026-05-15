package com.xa.mass.engine.strategy;

import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.WorkerReachabilityState;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;
import com.xa.mass.engine.model.WorkerSchedulingView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Transitional owner for expanding workers into scheduling candidates.
 *
 * <p>WorkerContext is still a legacy runtime resource during retirement. Keep
 * that storage read isolated here so matching strategy code can reason over
 * WorkerSchedulingCandidate / WorkerSchedulingView instead of directly owning
 * context enumeration.</p>
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
        List<String> workerIds = workers.stream().map(Worker::getWorkerId).toList();
        Map<String, List<WorkerContext>> contextsByWorkerId = workerManager.getWorkerContextsByWorkerIds(workerIds)
                .stream()
                .collect(Collectors.groupingBy(WorkerContext::getWorkerId));
        List<WorkerSchedulingCandidate> candidates = new ArrayList<>();
        for (Worker worker : workers) {
            List<WorkerContext> workerContexts = contextsByWorkerId.getOrDefault(worker.getWorkerId(), List.of());
            if (workerContexts.isEmpty()) {
                candidates.add(toSchedulingCandidate(worker, null));
                continue;
            }
            for (WorkerContext workerContext : workerContexts) {
                candidates.add(toSchedulingCandidate(worker, workerContext));
            }
        }
        return candidates;
    }

    private WorkerSchedulingCandidate toSchedulingCandidate(Worker worker, WorkerContext workerContext) {
        WorkerReachabilityState reachability = workerManager.getWorkerReachability(worker.getWorkerId());
        boolean dispatchEnabled = workerManager.isWorkerDispatchEnabled(worker);
        boolean workerLocked = workerManager.isLocked(worker.getWorkerId());
        return new WorkerSchedulingCandidate(
                worker,
                workerContext,
                WorkerSchedulingView.from(
                        worker,
                        workerContext,
                        reachability,
                        dispatchEnabled,
                        workerLocked,
                        workerManager.getWorkerLoad(worker.getWorkerId())
                )
        );
    }
}
