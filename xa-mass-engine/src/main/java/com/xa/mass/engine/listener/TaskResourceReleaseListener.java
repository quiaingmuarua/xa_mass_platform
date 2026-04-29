package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.base.model.WorkerContext;
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

    public TaskResourceReleaseListener(TaskRuntimeMaintenancePort maintenancePort,
                                       WorkerManager workerManager) {
        this.maintenancePort = maintenancePort;
        this.workerManager = workerManager;
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
            releaseWorkerContextIfOwnedByTask(
                    task.getTid(),
                    lease.workerId(),
                    lease.workerContextId()
            );
        }

        for (String workerId : workerIds) {
            workerManager.unlockWorker(workerId);
            TraceEventLogger.workerLockReleased(task.getTid(), workerId,
                    "ON_TASK_TERMINAL", "TaskResourceReleaseListener", "task reached terminal");
        }
    }

    public void onTaskMessageAttemptClosed(Task task, TaskMsg taskMsg, TaskMsgAttempt attempt) {
        if (task == null || taskMsg == null || attempt == null || task.getStatus().isFinal()) {
            return;
        }
        String workerId = attempt.getWorkerId();
        if (workerId == null || workerId.isBlank()) {
            return;
        }
        if (hasOtherActiveAttempts(task.getTid(), workerId)) {
            return;
        }

        releaseWorkerContextIfOwnedByTask(task.getTid(), workerId, attempt.getWorkerContextId());
        workerManager.unlockWorker(workerId);
        TraceEventLogger.workerLockReleased(task.getTid(), workerId,
                "ON_TASK_MESSAGE_ATTEMPT_CLOSED", "TaskResourceReleaseListener", "worker has no in-flight messages");

        if (task.getStatus() == TaskStatus.RUNNING
                && maintenancePort.hasPendingDispatchableMessages(task.getTid())) {
            maintenancePort.requestTaskDispatch(task);
        }
    }

    private boolean hasOtherActiveAttempts(String taskId, String workerId) {
        return maintenancePort.hasProcessingMessagesForWorker(taskId, workerId);
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
            TraceEventLogger.workerContextStatusTransition(
                    taskId,
                    workerContext,
                    fromStatus,
                    workerContext.getStatus(),
                    "RELEASE_WORKER_CONTEXT",
                    "TaskResourceReleaseListener",
                    "workerContext released after task/message completion"
            );
            TraceEventLogger.resourceReleased(taskId, workerId, workerContextId, "workerContext released");
            return;
        }

        TraceEventLogger.resourceReleaseFailed(taskId, workerId, workerContextId,
                "workerContext could not transition to IDLE from " + workerContext.getStatus());
        log.warn("WorkerContext {} on worker {} could not be released from status {} for task {}",
                workerContextId, workerId, workerContext.getStatus(), taskId);
    }
}

