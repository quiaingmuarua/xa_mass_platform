package com.xa.mass.task.runtime;

public record ActiveLeaseRepairCandidate(
        String taskId,
        String messageId,
        String leaseToken,
        String workerId,
        String workerGroupId,
        String batchId,
        String workerReservationToken,
        Long scoreBandClaimScore,
        int attemptNo,
        long leaseExpireAtMillis
) {

    public ActiveLeaseRepairCandidate(String taskId,
                                      String messageId,
                                      String leaseToken,
                                      String workerId,
                                      int attemptNo,
                                      long leaseExpireAtMillis) {
        this(taskId, messageId, leaseToken, workerId, "", "", "", null, attemptNo, leaseExpireAtMillis);
    }

    public ActiveLeaseRepairCandidate(String taskId,
                                      String messageId,
                                      String leaseToken,
                                      String workerId,
                                      String workerGroupId,
                                      String batchId,
                                      String workerReservationToken,
                                      int attemptNo,
                                      long leaseExpireAtMillis) {
        this(taskId, messageId, leaseToken, workerId, workerGroupId, batchId,
                workerReservationToken, null, attemptNo, leaseExpireAtMillis);
    }

    public ActiveLeaseRepairCandidate {
        taskId = TaskRuntimeContractChecks.requireText(taskId, "taskId");
        messageId = TaskRuntimeContractChecks.requireText(messageId, "messageId");
        leaseToken = TaskRuntimeContractChecks.requireText(leaseToken, "leaseToken");
        workerId = TaskRuntimeContractChecks.requireText(workerId, "workerId");
        workerGroupId = TaskRuntimeContractChecks.optionalText(workerGroupId);
        batchId = TaskRuntimeContractChecks.optionalText(batchId);
        workerReservationToken = TaskRuntimeContractChecks.optionalText(workerReservationToken);
        attemptNo = Math.max(1, attemptNo);
        leaseExpireAtMillis = Math.max(0L, leaseExpireAtMillis);
    }
}
