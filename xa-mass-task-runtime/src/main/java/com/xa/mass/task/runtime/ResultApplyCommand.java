package com.xa.mass.task.runtime;

import java.util.Map;

public record ResultApplyCommand(
        String taskId,
        String messageId,
        String leaseToken,
        String workerId,
        int attemptNo,
        ResultApplySource source,
        boolean success,
        Map<String, Object> resultPayloadJson,
        String failureReason,
        RetryPolicySnapshot retryPolicy,
        ResultFinalityPolicySnapshot finalityPolicy,
        RuntimeEpoch runtimeEpoch,
        long observedAtMillis
) {

    public ResultApplyCommand {
        taskId = TaskRuntimeContractChecks.requireText(taskId, "taskId");
        messageId = TaskRuntimeContractChecks.requireText(messageId, "messageId");
        leaseToken = TaskRuntimeContractChecks.requireText(leaseToken, "leaseToken");
        workerId = TaskRuntimeContractChecks.requireText(workerId, "workerId");
        attemptNo = Math.max(1, attemptNo);
        source = source == null ? ResultApplySource.WORKER_RESULT : source;
        resultPayloadJson = TaskRuntimeContractChecks.copyPayload(resultPayloadJson);
        failureReason = failureReason == null ? "" : failureReason;
        if (retryPolicy == null) {
            throw new IllegalArgumentException("retryPolicy is required");
        }
        if (finalityPolicy == null) {
            throw new IllegalArgumentException("finalityPolicy is required");
        }
        runtimeEpoch = runtimeEpoch == null ? RuntimeEpoch.of(taskId, 0L) : runtimeEpoch;
        observedAtMillis = Math.max(0L, observedAtMillis);
    }
}
