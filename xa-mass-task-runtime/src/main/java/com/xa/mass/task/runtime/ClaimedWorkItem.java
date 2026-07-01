package com.xa.mass.task.runtime;

import java.util.Map;

public record ClaimedWorkItem(
        String taskId,
        String messageId,
        String eventCode,
        Map<String, Object> payloadJson,
        String payloadRef,
        String leaseToken,
        String workerReservationToken,
        Long scoreBandClaimScore,
        String workerId,
        String workerGroupId,
        String batchId,
        int attemptNo,
        long leaseExpireAtMillis
) {

    public ClaimedWorkItem(String taskId,
                           String messageId,
                           String eventCode,
                           Map<String, Object> payloadJson,
                           String payloadRef,
                           String leaseToken,
                           String workerReservationToken,
                           String workerId,
                           String workerGroupId,
                           String batchId,
                           int attemptNo,
                           long leaseExpireAtMillis) {
        this(taskId, messageId, eventCode, payloadJson, payloadRef, leaseToken,
                workerReservationToken, null, workerId, workerGroupId, batchId,
                attemptNo, leaseExpireAtMillis);
    }

    public ClaimedWorkItem {
        taskId = TaskRuntimeContractChecks.requireText(taskId, "taskId");
        messageId = TaskRuntimeContractChecks.requireText(messageId, "messageId");
        eventCode = TaskRuntimeContractChecks.optionalText(eventCode);
        payloadJson = TaskRuntimeContractChecks.copyPayload(payloadJson);
        payloadRef = TaskRuntimeContractChecks.optionalText(payloadRef);
        leaseToken = TaskRuntimeContractChecks.requireText(leaseToken, "leaseToken");
        workerReservationToken = TaskRuntimeContractChecks.requireText(workerReservationToken, "workerReservationToken");
        workerId = TaskRuntimeContractChecks.requireText(workerId, "workerId");
        workerGroupId = TaskRuntimeContractChecks.requireText(workerGroupId, "workerGroupId");
        batchId = TaskRuntimeContractChecks.optionalText(batchId);
        attemptNo = Math.max(1, attemptNo);
        leaseExpireAtMillis = Math.max(0L, leaseExpireAtMillis);
    }
}
