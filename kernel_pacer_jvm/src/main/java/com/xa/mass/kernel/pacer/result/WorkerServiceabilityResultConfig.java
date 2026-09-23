package com.xa.mass.kernel.pacer.result;

record WorkerServiceabilityResultConfig(
        int resultReportLimit,
        long evidenceMaxAgeMillis
) {

    public static final int DEFAULT_RESULT_REPORT_LIMIT = 10;
    public static final long DEFAULT_EVIDENCE_MAX_AGE_MILLIS = 30_000;

    public WorkerServiceabilityResultConfig {
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
                DEFAULT_RESULT_REPORT_LIMIT,
                DEFAULT_EVIDENCE_MAX_AGE_MILLIS
        );
    }
}
