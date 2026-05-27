package com.xa.mass.testing.perf;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;
import com.xa.mass.engine.model.WorkerSchedulingView;
import com.xa.mass.engine.resource.DefaultWorkerDispatchResourcePolicy;
import com.xa.mass.engine.resource.WorkerDispatchResourcePolicy;
import com.xa.mass.engine.worker.WorkerManager;
import com.xa.mass.runtime.worker.WorkerCandidateRow;
import com.xa.mass.runtime.worker.WorkerReachabilityState;

final class PerfWorkerMatchingSupport {

    private static final WorkerDispatchResourcePolicy RESOURCE_POLICY = new DefaultWorkerDispatchResourcePolicy();

    private PerfWorkerMatchingSupport() {
    }

    static WorkerSchedulingCandidate tryReserveCandidate(WorkerManager workerManager, Task task, Worker worker) {
        if (workerManager == null || task == null || worker == null
                || worker.getWorkerId() == null || worker.getWorkerId().isBlank()) {
            return null;
        }
        String workerId = worker.getWorkerId();
        if (!workerManager.tryReserveWorkerCapacity(workerId, task.getTid())) {
            return null;
        }
        WorkerSchedulingCandidate candidate = candidate(workerManager, worker);
        if (RESOURCE_POLICY.usageForCandidate(task, candidate).exclusiveWorkerLock()
                && !workerManager.tryAcquireWorkerExclusiveLease(workerId)) {
            workerManager.releaseWorkerReservation(workerId, task.getTid());
            return null;
        }
        return candidate(workerManager, worker);
    }

    private static WorkerSchedulingCandidate candidate(WorkerManager workerManager, Worker worker) {
        String workerId = worker.getWorkerId();
        WorkerCandidateRow candidateRow = candidateRow(worker);
        return new WorkerSchedulingCandidate(
                candidateRow,
                WorkerSchedulingView.from(
                        candidateRow,
                        workerManager.getWorkerReachability(workerId) == WorkerReachabilityState.UNKNOWN
                                ? WorkerReachabilityState.ONLINE
                                : workerManager.getWorkerReachability(workerId),
                        workerManager.isWorkerDispatchEnabled(worker),
                        workerManager.hasWorkerExclusiveLease(workerId),
                        workerManager.getWorkerLoad(workerId)
                )
        );
    }

    private static WorkerCandidateRow candidateRow(Worker worker) {
        return new WorkerCandidateRow(
                worker.getWorkerId(),
                worker.getStatus() == null ? null : worker.getStatus().name(),
                worker.getAgentVersion(),
                worker.getLastHeartbeat(),
                worker.getSupportedProjects(),
                worker.getSupportedEventCodes(),
                worker.getWorkerGroupId(),
                worker.getAdapterNodeId(),
                worker.getAdapterId(),
                worker.getOnlineStrategy(),
                worker.getMaxConcurrentWork(),
                worker.getAttributes(),
                worker.getCreateTime(),
                worker.getUpdateTime(),
                worker.isAvailable()
        );
    }
}
