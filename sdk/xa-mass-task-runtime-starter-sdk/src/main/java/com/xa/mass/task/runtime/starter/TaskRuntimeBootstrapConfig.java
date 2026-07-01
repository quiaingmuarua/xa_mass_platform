package com.xa.mass.task.runtime.starter;

public record TaskRuntimeBootstrapConfig(
        TaskRuntimeBackendKind backendKind,
        String redisUri,
        String redisNamespace,
        long loopIntervalMillis
) {

    private static final long DEFAULT_LOOP_INTERVAL_MILLIS = 100L;

    public TaskRuntimeBootstrapConfig {
        backendKind = backendKind == null ? TaskRuntimeBackendKind.MEMORY : backendKind;
        loopIntervalMillis = loopIntervalMillis <= 0 ? DEFAULT_LOOP_INTERVAL_MILLIS : loopIntervalMillis;
        redisUri = normalize(redisUri);
        redisNamespace = normalize(redisNamespace);
        if (backendKind == TaskRuntimeBackendKind.REDIS) {
            if (redisUri == null) {
                throw new IllegalArgumentException("redisUri is required for REDIS task runtime backend");
            }
            if (redisNamespace == null) {
                throw new IllegalArgumentException("redisNamespace is required for REDIS task runtime backend");
            }
        }
    }

    public static TaskRuntimeBootstrapConfig memory() {
        return new TaskRuntimeBootstrapConfig(TaskRuntimeBackendKind.MEMORY, null, null, DEFAULT_LOOP_INTERVAL_MILLIS);
    }

    public static TaskRuntimeBootstrapConfig redis(String redisUri, String redisNamespace) {
        return new TaskRuntimeBootstrapConfig(TaskRuntimeBackendKind.REDIS, redisUri, redisNamespace, DEFAULT_LOOP_INTERVAL_MILLIS);
    }

    public TaskRuntimeBootstrapConfig withLoopIntervalMillis(long loopIntervalMillis) {
        return new TaskRuntimeBootstrapConfig(backendKind, redisUri, redisNamespace, loopIntervalMillis);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
