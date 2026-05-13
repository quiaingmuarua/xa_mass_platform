package com.xa.mass.api.model.task;

public record ApiTaskUpdateOutcome(
        String taskId,
        String status,
        String intakeStatus,
        String message
) {
}
