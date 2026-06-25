package com.xa.mass.testing.perf;

import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.worker.runtime.evidence.WorkerSchedulingViewRuntime;
import com.xa.mass.worker.runtime.resource.WorkerResourceQueryRuntime;
import com.xa.mass.worker.runtime.resource.WorkerResourceRecord;

import java.util.Map;

final class PerfWorkerMatchingSupport {

    private PerfWorkerMatchingSupport() {
    }

    static boolean workerAvailable(WorkerSchedulingViewRuntime schedulingViewRuntime,
                                   WorkerResourceRecord worker) {
        if (schedulingViewRuntime == null || worker == null
                || worker.workerId() == null || worker.workerId().isBlank()) {
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

    static boolean isBulkTask(Map<String, TaskWorkloadClass> workloadByTaskId, String taskId) {
        return taskId != null && workloadByTaskId != null && workloadByTaskId.get(taskId) == TaskWorkloadClass.BULK;
    }
}
