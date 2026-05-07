package com.xa.mass.engine;

import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.engine.util.TraceEventLogger;
import com.xa.mass.runtime.api.ActiveLeaseRecord;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/**
 * Repairs compatibility TaskMsg/TaskMsgAttempt state from runtime lease truth.
 */
final class RuntimeLeaseProjectionSupport {

    private RuntimeLeaseProjectionSupport() {
    }

    static ProjectionLeaseSyncResult recoverAndSynchronizeActiveAttempt(TaskLeaseProjectionPort projectionPort,
                                                                        String taskId,
                                                                        TaskMsg taskMsg,
                                                                        ActiveLeaseRecord activeLease,
                                                                        TraceEventLogger traceEventLogger,
                                                                        String trigger,
                                                                        String reason) {
        if (projectionPort == null || taskMsg == null || activeLease == null) {
            return ProjectionLeaseSyncResult.rejected(null);
        }
        TaskMsgAttempt activeAttempt = resolveOrRecoverActiveAttempt(projectionPort, taskMsg, activeLease);
        if (!synchronizeProjectionFromRuntimeLease(
                projectionPort,
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

    static TaskMsgAttempt resolveOrRecoverActiveAttempt(TaskLeaseProjectionPort projectionPort,
                                                        TaskMsg taskMsg,
                                                        ActiveLeaseRecord activeLease) {
        if (projectionPort == null || taskMsg == null || activeLease == null) {
            return null;
        }
        TaskMsgAttempt activeAttempt = projectionPort.getLatestActiveTaskMessageAttempt(taskMsg.getTaskId(), taskMsg.getMessageId());
        if (activeAttempt != null) {
            return activeAttempt;
        }
        int nextAttemptNo = activeLease.retryCount() + 1;
        TaskMsgAttempt recoveredAttempt = new TaskMsgAttempt(
                "recovered-attempt-" + taskMsg.getMessageId() + "-" + nextAttemptNo + "-" + UUID.randomUUID(),
                taskMsg.getTaskId(),
                taskMsg.getMessageId(),
                nextAttemptNo
        );
        recoveredAttempt.setWorkerId(activeLease.workerId());
        recoveredAttempt.setWorkerContextId(activeLease.workerContextId());
        recoveredAttempt.setBatchId(activeLease.batchId());
        if (!recoveredAttempt.markLeased(LocalDateTime.ofInstant(activeLease.leaseExpireAt(), ZoneId.systemDefault()))) {
            return null;
        }
        if (!recoveredAttempt.markDispatched()) {
            return null;
        }
        projectionPort.addTaskMessageAttempt(taskMsg.getTaskId(), taskMsg.getMessageId(), recoveredAttempt);
        return recoveredAttempt;
    }

    static boolean synchronizeProjectionFromRuntimeLease(TaskLeaseProjectionPort projectionPort,
                                                         String taskId,
                                                         TaskMsg taskMsg,
                                                         TaskMsgAttempt activeAttempt,
                                                         ActiveLeaseRecord activeLease,
                                                         TraceEventLogger traceEventLogger,
                                                         String trigger,
                                                         String reason) {
        if (projectionPort == null || taskMsg == null || activeLease == null) {
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
        return projectionPort.updateTaskMessage(taskId, taskMsg);
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
