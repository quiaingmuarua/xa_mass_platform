package com.xa.mass.testing.perf;

import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.worker.runtime.candidate.WorkerCandidateBatch;
import com.xa.mass.worker.runtime.candidate.WorkerCandidateRow;
import com.xa.mass.worker.runtime.candidate.WorkerCandidateRuntime;
import com.xa.mass.worker.runtime.candidate.WorkerTaskSelector;
import com.xa.mass.worker.runtime.evidence.WorkerReachabilityState;
import com.xa.mass.worker.runtime.evidence.WorkerSchedulingViewRuntime;
import com.xa.mass.worker.runtime.resource.WorkerResourceQueryRuntime;
import com.xa.mass.worker.runtime.resource.WorkerResourceRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class PerfWorkerMatchingSupport {

    private PerfWorkerMatchingSupport() {
    }

    static WorkerCandidateRuntime deterministicCandidateRuntime(WorkerResourceQueryRuntime workerResourceQueryRuntime) {
        return new CandidateRuntime(workerResourceQueryRuntime, Map.of(), 0);
    }

    static WorkerCandidateRuntime laneAwareCandidateRuntime(WorkerResourceQueryRuntime workerResourceQueryRuntime,
                                                            Map<String, TaskWorkloadClass> workloadByTaskId,
                                                            int reservedInteractiveWorkers) {
        return new CandidateRuntime(workerResourceQueryRuntime, workloadByTaskId, reservedInteractiveWorkers);
    }

    static boolean workerAvailable(WorkerSchedulingViewRuntime schedulingViewRuntime,
                                   WorkerResourceRecord worker) {
        if (schedulingViewRuntime == null || worker == null
                || worker.workerId() == null || worker.workerId().isBlank()) {
            return false;
        }
        WorkerReachabilityState reachability = schedulingViewRuntime.getWorkerReachability(worker.workerId());
        if (reachability == WorkerReachabilityState.OFFLINE) {
            return false;
        }
        return schedulingViewRuntime.isWorkerDispatchEnabled(worker.workerId())
                && !schedulingViewRuntime.hasWorkerExclusiveLease(worker.workerId());
    }

    static boolean supportsProject(WorkerSchedulingViewRuntime schedulingViewRuntime,
                                   WorkerResourceRecord worker,
                                   String projectCode) {
        if (projectCode == null || projectCode.isBlank() || schedulingViewRuntime == null
                || worker == null || worker.workerGroupId() == null || worker.workerGroupId().isBlank()) {
            return false;
        }
        return schedulingViewRuntime.workerGroupReadView(worker.workerGroupId())
                .map(view -> view.projectCodes().contains(projectCode))
                .orElse(false);
    }

    static boolean isReservedInteractiveWorker(WorkerResourceQueryRuntime workerResourceQueryRuntime,
                                               WorkerResourceRecord worker,
                                               int reservedInteractiveWorkers) {
        if (reservedInteractiveWorkers <= 0 || workerResourceQueryRuntime == null
                || worker == null || worker.workerId() == null) {
            return false;
        }
        String workerId = worker.workerId();
        int dash = workerId.lastIndexOf('-');
        if (dash < 0 || dash == workerId.length() - 1) {
            return false;
        }
        try {
            int workerIndex = Integer.parseInt(workerId.substring(dash + 1));
            int totalWorkers = workerResourceQueryRuntime.workers().size();
            return workerIndex >= Math.max(totalWorkers - reservedInteractiveWorkers, 0);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static WorkerCandidateRow candidateRow(WorkerResourceRecord worker) {
        return new WorkerCandidateRow(
                worker.workerId(),
                worker.agentVersion(),
                worker.workerGroupId(),
                worker.transportHint(),
                worker.attributes()
        );
    }

    private static final class CandidateRuntime implements WorkerCandidateRuntime {
        private final WorkerResourceQueryRuntime workerResourceQueryRuntime;
        private final Map<String, TaskWorkloadClass> workloadByTaskId;
        private final int reservedInteractiveWorkers;

        private CandidateRuntime(WorkerResourceQueryRuntime workerResourceQueryRuntime,
                                 Map<String, TaskWorkloadClass> workloadByTaskId,
                                 int reservedInteractiveWorkers) {
            this.workerResourceQueryRuntime = Objects.requireNonNull(workerResourceQueryRuntime,
                    "workerResourceQueryRuntime");
            this.workloadByTaskId = workloadByTaskId == null ? Map.of() : workloadByTaskId;
            this.reservedInteractiveWorkers = Math.max(reservedInteractiveWorkers, 0);
        }

        @Override
        public WorkerCandidateBatch<WorkerCandidateRow> findWorkerCandidateBatch(WorkerTaskSelector selector,
                                                                                 int maxCandidateCount) {
            if (selector == null || maxCandidateCount <= 0 || selector.workerGroupIds().isEmpty()) {
                return WorkerCandidateBatch.empty();
            }
            List<WorkerCandidateRow> rows = new ArrayList<>();
            for (WorkerResourceRecord worker : workerResourceQueryRuntime.workers()) {
                if (rows.size() >= maxCandidateCount) {
                    break;
                }
                if (worker == null || !selector.workerGroupIds().contains(worker.workerGroupId())) {
                    continue;
                }
                if (selector.targetWorkerId() != null && !selector.targetWorkerId().equals(worker.workerId())) {
                    continue;
                }
                if (isBulkTask(selector.taskId())
                        && isReservedInteractiveWorker(workerResourceQueryRuntime, worker, reservedInteractiveWorkers)) {
                    continue;
                }
                rows.add(candidateRow(worker));
            }
            return new WorkerCandidateBatch<>(rows, 0, rows.size(), 0);
        }

        private boolean isBulkTask(String taskId) {
            return taskId != null && workloadByTaskId.get(taskId) == TaskWorkloadClass.BULK;
        }
    }
}
