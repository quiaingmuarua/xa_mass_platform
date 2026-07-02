package com.xa.mass.engine.model;

/**
 * Bounded engine-owned command result for task shell lifecycle mutations.
 */
public record TaskCommandOutcome(
        String taskId,
        boolean accepted,
        boolean applied,
        String reasonCode,
        String message
) {

    public TaskCommandOutcome {
        accepted = accepted || applied;
        reasonCode = normalize(reasonCode);
        message = normalize(message);
    }

    public static TaskCommandOutcome applied(String taskId, String reasonCode, String message) {
        return new TaskCommandOutcome(taskId, true, true, reasonCode, message);
    }

    public static TaskCommandOutcome alreadyApplied(String taskId, String reasonCode, String message) {
        return new TaskCommandOutcome(taskId, true, false, reasonCode, message);
    }

    public static TaskCommandOutcome rejected(String taskId, String reasonCode, String message) {
        return new TaskCommandOutcome(taskId, false, false, reasonCode, message);
    }

    public static TaskCommandOutcome conflict(String taskId, String reasonCode, String message) {
        return new TaskCommandOutcome(taskId, false, false, reasonCode, message);
    }

    public static TaskCommandOutcome notFound(String taskId) {
        return new TaskCommandOutcome(taskId, false, false, "TASK_NOT_FOUND", "Task not found");
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
