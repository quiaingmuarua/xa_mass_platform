package com.xa.mass.task.runtime;

public record TaskCloseAttemptOutcome(String taskId, boolean closed, String reason) {

    public TaskCloseAttemptOutcome {
        taskId = TaskRuntimeContractChecks.requireText(taskId, "taskId");
        reason = reason == null ? "" : reason;
    }

    public static TaskCloseAttemptOutcome deferred(String taskId, String reason) {
        return new TaskCloseAttemptOutcome(taskId, false, reason);
    }
}
