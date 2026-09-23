package com.xa.mass.kernel.pacer.dispatch;

import java.util.List;

record WorkerServiceabilityDispatchConfig(
        long intervalMillis,
        long hotEligibilityFloorMillis,
        long recheckDelayMillis,
        long hotProbeStaleAfterMillis,
        List<String> probeExcludedEndpointManagerIds
) {

    public static final long DEFAULT_INTERVAL_MILLIS = 1_000;
    public static final long DEFAULT_RECHECK_DELAY_MILLIS = 15_000;
    public static final long DEFAULT_HOT_PROBE_STALE_AFTER_MILLIS = 60_000;
    public static final List<String> DEFAULT_PROBE_EXCLUDED_ENDPOINT_IDS =
            List.of("system-polling");

    public WorkerServiceabilityDispatchConfig {
        if (intervalMillis < 1
                || recheckDelayMillis < 1
                || hotProbeStaleAfterMillis < 1) {
            throw new IllegalArgumentException(
                    "serviceability durations must be positive"
            );
        }
        requireFloor(hotEligibilityFloorMillis);
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

    public static WorkerServiceabilityDispatchConfig defaults(
            long hotEligibilityFloorMillis
    ) {
        return new WorkerServiceabilityDispatchConfig(
                DEFAULT_INTERVAL_MILLIS,
                hotEligibilityFloorMillis,
                DEFAULT_RECHECK_DELAY_MILLIS,
                DEFAULT_HOT_PROBE_STALE_AFTER_MILLIS,
                DEFAULT_PROBE_EXCLUDED_ENDPOINT_IDS
        );
    }

    static void requireFloor(long floor) {
        if (floor <= 0) {
            throw new IllegalArgumentException(
                    "hotEligibilityFloorMillis must be positive"
            );
        }
    }
}
