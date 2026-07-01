package com.xa.mass.task.runtime;

public record ActiveWorkQuery(String workerId, int limit) {

    public ActiveWorkQuery {
        workerId = TaskRuntimeContractChecks.requireText(workerId, "workerId");
        limit = Math.max(1, limit);
    }
}
