package com.xa.mass.task.runtime.command;

public record TaskRuntimeCommandOutcome(
        String taskId,
        TaskRuntimeCommandStatus status,
        boolean applied,
        String reasonCode,
        String message
) {

    public TaskRuntimeCommandOutcome {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId is required");
        }
        taskId = taskId.trim();
        status = status == null ? TaskRuntimeCommandStatus.REJECTED : status;
        reasonCode = normalize(reasonCode);
        message = normalize(message);
    }

    public boolean accepted() {
        return status.accepted();
    }

    public static TaskRuntimeCommandOutcome applied(String taskId, String reasonCode, String message) {
        return new TaskRuntimeCommandOutcome(taskId, TaskRuntimeCommandStatus.APPLIED, true, reasonCode, message);
    }

    public static TaskRuntimeCommandOutcome alreadyApplied(String taskId, String reasonCode, String message) {
        return new TaskRuntimeCommandOutcome(taskId, TaskRuntimeCommandStatus.ALREADY_APPLIED, false, reasonCode, message);
    }

    public static TaskRuntimeCommandOutcome conflict(String taskId, String reasonCode, String message) {
        return new TaskRuntimeCommandOutcome(taskId, TaskRuntimeCommandStatus.CONFLICT, false, reasonCode, message);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
