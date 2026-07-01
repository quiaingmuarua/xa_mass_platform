package com.xa.mass.task.runtime;

public record RuntimeEpoch(String taskId, long epoch, String fenceToken) {

    private static final RuntimeEpoch UNSPECIFIED = new RuntimeEpoch("_unspecified", 0L, null);

    public RuntimeEpoch {
        taskId = TaskRuntimeContractChecks.requireText(taskId, "taskId");
        epoch = Math.max(0L, epoch);
        fenceToken = fenceToken == null || fenceToken.isBlank() ? null : fenceToken;
    }

    public static RuntimeEpoch of(String taskId, long epoch) {
        return new RuntimeEpoch(taskId, epoch, null);
    }

    public static RuntimeEpoch unspecified() {
        return UNSPECIFIED;
    }
}
