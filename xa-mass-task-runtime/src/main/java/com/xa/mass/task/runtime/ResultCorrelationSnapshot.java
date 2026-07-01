package com.xa.mass.task.runtime;

public record ResultCorrelationSnapshot(
        String taskId,
        String messageId,
        String leaseToken,
        String workerId,
        int attemptNo,
        boolean present
) {

    public ResultCorrelationSnapshot {
        taskId = TaskRuntimeContractChecks.requireText(taskId, "taskId");
        messageId = TaskRuntimeContractChecks.requireText(messageId, "messageId");
        leaseToken = present ? TaskRuntimeContractChecks.requireText(leaseToken, "leaseToken") : "";
        workerId = present ? TaskRuntimeContractChecks.requireText(workerId, "workerId") : "";
        attemptNo = present ? Math.max(1, attemptNo) : 0;
    }

    public static ResultCorrelationSnapshot missing(String taskId, String messageId) {
        return new ResultCorrelationSnapshot(taskId, messageId, "", "", 0, false);
    }
}
