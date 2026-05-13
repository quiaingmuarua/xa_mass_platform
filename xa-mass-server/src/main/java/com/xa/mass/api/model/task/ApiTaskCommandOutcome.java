package com.xa.mass.api.model.task;

public record ApiTaskCommandOutcome(
        String taskId,
        String command,
        boolean accepted,
        String status,
        String intakeStatus,
        String terminalReason,
        String holdReason,
        String failureReason,
        String reasonCode
) {
}
