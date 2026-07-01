package com.xa.mass.task.runtime;

public record ActiveTaskWorkQuery(String taskId, int limit) {

    public ActiveTaskWorkQuery {
        taskId = TaskRuntimeContractChecks.requireText(taskId, "taskId");
        limit = Math.max(1, limit);
    }
}
