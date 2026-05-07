package com.xa.mass.engine;

import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.engine.util.TraceEventLogger;
import com.xa.mass.runtime.api.ActiveLeaseRecord;

import java.util.Objects;
import java.util.UUID;

/**
 * Repairs compatibility TaskMsg/TaskMsgAttempt state from runtime lease truth.
 */
final class RuntimeLeaseProjectionSupport {

    private RuntimeLeaseProjectionSupport() {
    }

    static ProjectionLeaseSyncResult recoverAndSynchronizeActiveAttempt(TaskManager taskManager,
                                                                        String taskId,
                                                                        TaskMsg taskMsg,
                                                                        ActiveLeaseRecord activeLease,
                                                                        TraceEventLogger traceEventLogger,
                                                                        String trigger,
                                                                        String reason) {
        if (taskManager == null || taskMsg == null || activeLease == null) {
            return ProjectionLeaseSyncResult.rejected(null);
        }
        TaskMsgAttempt activeAttempt = resolveOrRecoverActiveAttempt(taskManager, taskMsg, activeLease);
        if (!synchronizeProjectionFromRuntimeLease(
                taskManager,
                taskId,
                taskMsg,
                activeAttempt,
                activeLease,
                traceEventLogger,
                trigger,
                reason)) {
            return ProjectionLeaseSyncResult.rejected(activeAttempt);
        }
        return ProjectionLeaseSyncResult.accepted(activeAttempt);
    }

    static TaskMsgAttempt resolveOrRecoverActiveAttempt(TaskManager taskManager,
                                                        TaskMsg taskMsg,
                                                        ActiveLeaseRecord activeLease) {
        if (taskManager == null || taskMsg == null || activeLease == null) {
            return null;
        }
        TaskMsgAttempt activeAttempt = taskManager.getLatestActiveTaskMessageAttempt(taskMsg.getTaskId(), taskMsg.getMessageId());
        if (activeAttempt != null) {
            return activeAttempt;
        }
        int nextAttemptNo = activeLease.retryCount() + 1;
        String recoveredAttemptId = taskMsg.latestAttemptId();
        if (recoveredAttemptId == null || recoveredAttemptId.isBlank()) {
            recoveredAttemptId = "recovered-attempt-" + taskMsg.getMessageId() + "-" + nextAttemptNo + "-" + UUID.randomUUID();
        }
        TaskMsgAttempt recoveredAttempt = TaskMessageAttemptSupport.buildDispatchedProjection(
                recoveredAttemptId,
                taskMsg.getTaskId(),
                taskMsg.getMessageId(),
                nextAttemptNo,
                activeLease.workerId(),
                activeLease.workerContextId(),
                activeLease.batchId(),
                activeLease.leaseExpireAt()
        );
        tryAddTaskMessageAttempt(taskManager, taskMsg.getTaskId(), taskMsg.getMessageId(), recoveredAttempt);
        return recoveredAttempt;
    }

    static boolean synchronizeProjectionFromRuntimeLease(TaskManager taskManager,
                                                         String taskId,
                                                         TaskMsg taskMsg,
                                                         TaskMsgAttempt activeAttempt,
                                                         ActiveLeaseRecord activeLease,
                                                         TraceEventLogger traceEventLogger,
                                                         String trigger,
                                                         String reason) {
        if (taskManager == null || taskMsg == null || activeLease == null) {
            return false;
        }
        boolean projectionChanged = false;
        if (!Objects.equals(taskMsg.latestAttemptId(), activeAttempt != null ? activeAttempt.getAttemptId() : null)
                || !Objects.equals(taskMsg.getLatestAttemptWorkerId(), activeLease.workerId())
                || !Objects.equals(taskMsg.getLatestAttemptWorkerContextId(), activeLease.workerContextId())
                || !Objects.equals(taskMsg.getLatestAttemptBatchId(), activeLease.batchId())) {
            taskMsg.applyLatestAttemptProjection(
                    activeAttempt != null ? activeAttempt.getAttemptId() : null,
                    activeLease.workerId(),
                    activeLease.workerContextId(),
                    activeLease.batchId()
            );
            projectionChanged = true;
        }
        if (taskMsg.getStatus() == TaskMsgStatus.INIT) {
            TaskMsgStatus fromStatus = taskMsg.getStatus();
            if (!taskMsg.markAsAssigned()) {
                return false;
            }
            traceEventLogger.taskMsgStatusTransition(
                    taskMsg,
                    activeAttempt,
                    fromStatus,
                    taskMsg.getStatus(),
                    trigger,
                    "TaskManager",
                    reason
            );
            projectionChanged = true;
        }
        if (!projectionChanged) {
            return true;
        }
        return taskManager.updateTaskMessage(taskId, taskMsg);
    }

    private static void tryAddTaskMessageAttempt(TaskManager taskManager,
                                                 String taskId,
                                                 String messageId,
                                                 TaskMsgAttempt attempt) {
        if (taskManager == null || attempt == null) {
            return;
        }
        try {
            taskManager.addTaskMessageAttempt(taskId, messageId, attempt);
        } catch (RuntimeException ignored) {
            // Compatibility attempt persistence is best-effort during runtime recovery.
        }
    }

    static final class ProjectionLeaseSyncResult {
        private final TaskMsgAttempt activeAttempt;
        private final boolean synchronizedProjection;

        private ProjectionLeaseSyncResult(TaskMsgAttempt activeAttempt, boolean synchronizedProjection) {
            this.activeAttempt = activeAttempt;
            this.synchronizedProjection = synchronizedProjection;
        }

        static ProjectionLeaseSyncResult accepted(TaskMsgAttempt activeAttempt) {
            return new ProjectionLeaseSyncResult(activeAttempt, true);
        }

        static ProjectionLeaseSyncResult rejected(TaskMsgAttempt activeAttempt) {
            return new ProjectionLeaseSyncResult(activeAttempt, false);
        }

        TaskMsgAttempt activeAttempt() {
            return activeAttempt;
        }

        boolean synchronizedProjection() {
            return synchronizedProjection;
        }
    }
}
