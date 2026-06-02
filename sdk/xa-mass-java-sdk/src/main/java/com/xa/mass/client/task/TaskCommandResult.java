package com.xa.mass.client.task;

public record TaskCommandResult(
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
