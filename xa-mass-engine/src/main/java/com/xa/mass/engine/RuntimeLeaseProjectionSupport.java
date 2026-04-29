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
        TaskMsgAttempt latestAttempt = projectionPort.getLatestTaskMessageAttempt(taskMsg.getTaskId(), taskMsg.getMessageId());
        int nextAttemptNo = Math.max(activeLease.retryCount() + 1, latestAttempt != null ? latestAttempt.getAttemptNo() + 1 : 1);
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
            TraceEventLogger.taskMsgStatusTransition(
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
}

