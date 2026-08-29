package com.xa.mass.kernel.pacer.dispatch;

import java.util.List;

record WorkerServiceabilityDispatchConfig(
        long recoveryRetryIntervalMillis,
        long probeSweepRestartDelayMillis,
        int maxRecoveryAttempts,
        List<String> probeExcludedEndpointManagerIds
) {

    public static final long DEFAULT_RECOVERY_RETRY_INTERVAL_MILLIS = 60_000;
    public static final long DEFAULT_PROBE_SWEEP_RESTART_DELAY_MILLIS = 10_000;
    public static final int DEFAULT_MAX_RECOVERY_ATTEMPTS = 5;
    public static final List<String> DEFAULT_PROBE_EXCLUDED_ENDPOINT_IDS =
            List.of("system-polling");

    public WorkerServiceabilityDispatchConfig {
        if (recoveryRetryIntervalMillis < 1
                || probeSweepRestartDelayMillis < 1) {
            throw new IllegalArgumentException(
                    "serviceability durations must be positive"
            );
        }
        if (maxRecoveryAttempts < 1
                || maxRecoveryAttempts > WorkerServiceabilityDispatchMechanism
                .MAX_RECOVERY_ATTEMPTS) {
            throw new IllegalArgumentException(
                    "maxRecoveryAttempts must be between 1 and 99"
            );
        }
        if (probeExcludedEndpointManagerIds == null
                || probeExcludedEndpointManagerIds.size() > 100) {
            throw new IllegalArgumentException(
                    "probe excluded Endpoint ids must contain at most 100 ids"
            );
        }
        if (probeExcludedEndpointManagerIds.stream()
                .anyMatch(value -> value == null || value.isEmpty())
                || probeExcludedEndpointManagerIds.stream().distinct().count()
                != probeExcludedEndpointManagerIds.size()) {
            throw new IllegalArgumentException(
                    "probe excluded Endpoint ids must be unique and non-empty"
            );
        }
        probeExcludedEndpointManagerIds = List.copyOf(
                probeExcludedEndpointManagerIds
        );
    }

    public static WorkerServiceabilityDispatchConfig defaults() {
        return new WorkerServiceabilityDispatchConfig(
                DEFAULT_RECOVERY_RETRY_INTERVAL_MILLIS,
                DEFAULT_PROBE_SWEEP_RESTART_DELAY_MILLIS,
                DEFAULT_MAX_RECOVERY_ATTEMPTS,
                DEFAULT_PROBE_EXCLUDED_ENDPOINT_IDS
        );
    }
}
