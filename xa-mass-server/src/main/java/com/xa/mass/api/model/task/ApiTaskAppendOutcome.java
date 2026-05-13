package com.xa.mass.api.model.task;

public record ApiTaskAppendOutcome(
        String taskId,
        int added,
        String status,
        String intakeStatus,
        String message
) {
}
