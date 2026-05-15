package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.TaskWorkAttemptClosedEvent;
import com.xa.mass.engine.TaskRuntimeMaintenancePort;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.util.TraceEventLogger;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Releases runtime-only resource occupancy when a task reaches TERMINAL.
 */
public class TaskResourceReleaseListener {

    private static final Logger log = LoggerFactory.getLogger(TaskResourceReleaseListener.class);

    private final TaskRuntimeMaintenancePort maintenancePort;
    private final WorkerManager workerManager;
    private final TraceEventLogger traceEventLogger;

    public TaskResourceReleaseListener(TaskRuntimeMaintenancePort maintenancePort,
                                       WorkerManager workerManager) {
        this(maintenancePort, workerManager, TraceEventLogger.noop());
    }

    public TaskResourceReleaseListener(TaskRuntimeMaintenancePort maintenancePort,
                                       WorkerManager workerManager,
                                       TraceEventLogger traceEventLogger) {
        this.maintenancePort = maintenancePort;
        this.workerManager = workerManager;
        this.traceEventLogger = traceEventLogger;
    }

    public void onTaskTerminal(Task task) {
        if (task == null) {
            return;
        }

        List<ActiveLeaseRecord> leases = maintenancePort.getActiveLeases(task.getTid());
        Set<String> workerIds = new LinkedHashSet<>();

        for (ActiveLeaseRecord lease : leases) {
            if (lease == null || lease.workerId() == null || lease.workerId().isBlank()) {
                continue;
            }
            workerIds.add(lease.workerId());
            workerManager.recordWorkFinal(lease.workerId(), task.getTid());
            releaseWorkerContextIfOwnedByTask(
                    task.getTid(),
                    lease.workerId(),
                    lease.workerContextId()
            );
        }

        if (usesExclusiveWorkerLock(task)) {
            for (String workerId : workerIds) {
                workerManager.unlockWorker(workerId);
                traceEventLogger.workerLockReleased(task.getTid(), workerId,
                        "ON_TASK_TERMINAL", "TaskResourceReleaseListener", "task reached terminal");
            }
        }
    }

    public void onTaskWorkAttemptClosed(Task task, TaskWorkAttemptClosedEvent event) {
        if (task == null || event == null) {
            return;
        }
        String workerId = event.workerId();
        if (workerId == null || workerId.isBlank()) {
            return;
        }
        workerManager.recordWorkFinal(workerId, task.getTid());
        if (hasOtherActiveAttempts(task.getTid(), workerId)) {
            return;
        }

        releaseWorkerContextIfOwnedByTask(task.getTid(), workerId, event.workerContextId());
        if (usesExclusiveWorkerLock(task)) {
            workerManager.unlockWorker(workerId);
            traceEventLogger.workerLockReleased(task.getTid(), workerId,
                    "ON_TASK_MESSAGE_ATTEMPT_CLOSED", "TaskResourceReleaseListener", "worker has no in-flight messages");
        }

        if (task.getStatus() == TaskStatus.RUNNING
                && maintenancePort.hasDispatchReadyWork(task.getTid())) {
            maintenancePort.requestTaskDispatch(task);
        }
    }

    private boolean hasOtherActiveAttempts(String taskId, String workerId) {
        return maintenancePort.hasActiveWorkForWorker(taskId, workerId);
    }

    private boolean usesExclusiveWorkerLock(Task task) {
        return task == null || task.getExecutionSpec() == null || task.getExecutionSpec().isForeground();
    }

    private void releaseWorkerContextIfOwnedByTask(String taskId, String workerId, String workerContextId) {
        if (workerContextId == null || workerContextId.isBlank()) {
            return;
        }

        WorkerContext workerContext = workerManager.getWorkerContextById(workerContextId);
        if (workerContext == null || !workerId.equals(workerContext.getWorkerId())) {
            return;
        }
        if (workerContext.getLastBindTaskId() != null && !taskId.equals(workerContext.getLastBindTaskId())) {
            return;
        }
        if (workerContext.getStatus() == WorkerContextStatus.IDLE) {
            return;
        }
        WorkerContextStatus fromStatus = workerContext.getStatus();
        if (workerContext.release()) {
            workerManager.updateWorkerContextById(workerContextId, workerContext);
            traceEventLogger.workerContextStatusTransition(
                    taskId,
                    workerContext,
                    fromStatus,
                    workerContext.getStatus(),
                    "RELEASE_WORKER_CONTEXT",
                    "TaskResourceReleaseListener",
                    "workerContext released after task/message completion"
            );
            traceEventLogger.resourceReleased(taskId, workerId, workerContextId, "workerContext released");
            return;
        }

        traceEventLogger.resourceReleaseFailed(taskId, workerId, workerContextId,
                "workerContext could not transition to IDLE from " + workerContext.getStatus());
        log.warn("WorkerContext {} on worker {} could not be released from status {} for task {}",
                workerContextId, workerId, workerContext.getStatus(), taskId);
    }
}

