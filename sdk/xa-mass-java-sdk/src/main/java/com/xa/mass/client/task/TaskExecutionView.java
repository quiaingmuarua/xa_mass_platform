package com.xa.mass.client.task;

public record TaskExecutionView(
        String profile,
        String workloadClass,
        int batchSize,
        int maxRuntimeSeconds,
        int defaultMaxRetryCount,
        boolean foreground
) {
}
