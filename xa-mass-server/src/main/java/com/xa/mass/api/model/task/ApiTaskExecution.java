package com.xa.mass.api.model.task;

public record ApiTaskExecution(
        String profile,
        String workloadClass,
        int batchSize,
        int maxRuntimeSeconds,
        int defaultMaxRetryCount
) {
}
