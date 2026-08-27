package com.xa.mass.kernel.pacer;

import com.xa.mass.kernel.score.WorkerScoreCore;

record WorkerServiceabilityResultConfig(
        int maxRecoveryAttempts,
        int resultReportLimit,
        long evidenceMaxAgeMillis
) {

    public static final int DEFAULT_MAX_RECOVERY_ATTEMPTS = 5;
    public static final int DEFAULT_RESULT_REPORT_LIMIT = 10;
    public static final long DEFAULT_EVIDENCE_MAX_AGE_MILLIS = 30_000;

    public WorkerServiceabilityResultConfig {
        if (maxRecoveryAttempts < 1
                || maxRecoveryAttempts > WorkerScoreCore.MAX_LANE_RANK) {
            throw new IllegalArgumentException(
                    "maxRecoveryAttempts must be between 1 and 99"
            );
        }
        if (resultReportLimit < 1 || resultReportLimit > 100) {
            throw new IllegalArgumentException(
                    "resultReportLimit must be between 1 and 100"
            );
        }
        if (evidenceMaxAgeMillis < 1) {
            throw new IllegalArgumentException(
                    "evidenceMaxAgeMillis must be positive"
            );
        }
    }

    public static WorkerServiceabilityResultConfig defaults() {
        return new WorkerServiceabilityResultConfig(
                DEFAULT_MAX_RECOVERY_ATTEMPTS,
                DEFAULT_RESULT_REPORT_LIMIT,
                DEFAULT_EVIDENCE_MAX_AGE_MILLIS
        );
    }
}
