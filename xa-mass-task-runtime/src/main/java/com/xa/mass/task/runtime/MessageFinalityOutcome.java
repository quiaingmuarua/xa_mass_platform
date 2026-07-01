package com.xa.mass.task.runtime;

public record MessageFinalityOutcome(
        MessageFinalityStatus status,
        String taskId,
        String messageId,
        int attemptNo,
        boolean progressDirty,
        boolean terminalCandidate,
        long retryAtMillis,
        long finalResultExpiresAtMillis,
        String reason
) {

    public MessageFinalityOutcome {
        status = status == null ? MessageFinalityStatus.REJECTED : status;
        taskId = TaskRuntimeContractChecks.requireText(taskId, "taskId");
        messageId = TaskRuntimeContractChecks.requireText(messageId, "messageId");
        attemptNo = Math.max(1, attemptNo);
        retryAtMillis = Math.max(0L, retryAtMillis);
        finalResultExpiresAtMillis = Math.max(0L, finalResultExpiresAtMillis);
        reason = reason == null ? "" : reason;
    }

    public static MessageFinalityOutcome logicalFinal(
            String taskId,
            String messageId,
            int attemptNo,
            long finalResultExpiresAtMillis
    ) {
        return new MessageFinalityOutcome(
                MessageFinalityStatus.LOGICAL_FINAL,
                taskId,
                messageId,
                attemptNo,
                true,
                true,
                0L,
                finalResultExpiresAtMillis,
                "");
    }

    public static MessageFinalityOutcome retryScheduled(
            String taskId,
            String messageId,
            int attemptNo,
            long retryAtMillis,
            String reason
    ) {
        return new MessageFinalityOutcome(
                MessageFinalityStatus.RETRY_SCHEDULED,
                taskId,
                messageId,
                attemptNo,
                true,
                false,
                retryAtMillis,
                0L,
                reason);
    }

    public static MessageFinalityOutcome duplicateOrLate(String taskId, String messageId, int attemptNo, String reason) {
        return new MessageFinalityOutcome(
                MessageFinalityStatus.DUPLICATE_OR_LATE,
                taskId,
                messageId,
                attemptNo,
                false,
                false,
                0L,
                0L,
                reason);
    }
}
