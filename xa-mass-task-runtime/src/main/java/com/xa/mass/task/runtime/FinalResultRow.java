package com.xa.mass.task.runtime;

import java.util.Map;

public record FinalResultRow(
        String taskId,
        String messageId,
        long seq,
        int attemptNo,
        String workerId,
        String batchId,
        ResultApplySource source,
        boolean success,
        Map<String, Object> resultPayloadJson,
        String failureReason,
        long finalizedAtMillis,
        long expiresAtMillis
) {

    public FinalResultRow {
        taskId = TaskRuntimeContractChecks.requireText(taskId, "taskId");
        messageId = TaskRuntimeContractChecks.requireText(messageId, "messageId");
        seq = Math.max(0L, seq);
        attemptNo = Math.max(1, attemptNo);
        workerId = TaskRuntimeContractChecks.optionalText(workerId);
        batchId = TaskRuntimeContractChecks.optionalText(batchId);
        source = source == null ? ResultApplySource.WORKER_RESULT : source;
        resultPayloadJson = TaskRuntimeContractChecks.copyPayload(resultPayloadJson);
        failureReason = failureReason == null ? "" : failureReason;
        finalizedAtMillis = Math.max(0L, finalizedAtMillis);
        expiresAtMillis = Math.max(0L, expiresAtMillis);
    }

    public FinalResultRow withSeq(long seq) {
        return new FinalResultRow(taskId, messageId, seq, attemptNo, workerId, batchId, source, success,
                resultPayloadJson, failureReason, finalizedAtMillis, expiresAtMillis);
    }
}
