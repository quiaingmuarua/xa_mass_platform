package com.xa.mass.client.task;

public record TaskCreateResult(
        TaskView task,
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
