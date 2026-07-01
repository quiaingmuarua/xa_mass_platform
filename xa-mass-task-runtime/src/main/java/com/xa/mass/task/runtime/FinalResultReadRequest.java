package com.xa.mass.task.runtime;

public record FinalResultReadRequest(String taskId, long afterSeq, int limit) {

    public FinalResultReadRequest {
        taskId = TaskRuntimeContractChecks.requireText(taskId, "taskId");
        afterSeq = Math.max(0L, afterSeq);
        limit = Math.max(1, limit);
    }
}
