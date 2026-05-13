package com.xa.mass.api.model.task;

public record ApiTaskCreateOutcome(
        ApiTask task,
        String taskId,
        String taskName,
        String project,
        String userId,
        String principalId,
        String contract,
        String intakeStatus,
        String message
) {
}
