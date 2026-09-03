package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.score.WorkerScoreCore;
import java.util.List;

record WorkerServiceabilityDispatchConfig(
        long intervalMillis,
        long hotEligibilityFloorMillis,
        long probeRetryIntervalMillis,
        long probeSweepRestartDelayMillis,
        int maxRecoveryAttempts,
        List<String> probeExcludedEndpointManagerIds
) {

    public static final long DEFAULT_INTERVAL_MILLIS = 1_000;
    public static final long DEFAULT_PROBE_RETRY_INTERVAL_MILLIS = 60_000;
    public static final long DEFAULT_PROBE_SWEEP_RESTART_DELAY_MILLIS = 10_000;
    public static final int DEFAULT_MAX_RECOVERY_ATTEMPTS = 5;
    public static final List<String> DEFAULT_PROBE_EXCLUDED_ENDPOINT_IDS =
            List.of("system-polling");

    public WorkerServiceabilityDispatchConfig {
        if (intervalMillis < 1
                || probeRetryIntervalMillis < 1
                || probeSweepRestartDelayMillis < 1) {
            throw new IllegalArgumentException(
                    "serviceability durations must be positive"
            );
        }
        requireFloor(hotEligibilityFloorMillis);
        if (maxRecoveryAttempts < 1
                || maxRecoveryAttempts > WorkerScoreCore.MAX_LANE_RANK) {
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

    public static WorkerServiceabilityDispatchConfig defaults(
            long hotEligibilityFloorMillis
    ) {
        return new WorkerServiceabilityDispatchConfig(
                DEFAULT_INTERVAL_MILLIS,
                hotEligibilityFloorMillis,
                DEFAULT_PROBE_RETRY_INTERVAL_MILLIS,
                DEFAULT_PROBE_SWEEP_RESTART_DELAY_MILLIS,
                DEFAULT_MAX_RECOVERY_ATTEMPTS,
                DEFAULT_PROBE_EXCLUDED_ENDPOINT_IDS
        );
    }

    static void requireFloor(long floor) {
        if (floor < WorkerScoreCore.SLOT_MILLIS
                || floor % WorkerScoreCore.SLOT_MILLIS != 0
                || floor > WorkerScoreCore.MAX_TIME_MILLIS) {
            throw new IllegalArgumentException(
                    "hotEligibilityFloorMillis must be a valid "
                            + "score-slot-aligned time"
            );
        }
    }
}
