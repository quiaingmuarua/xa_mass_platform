package com.xa.mass.kernel.pacer.result;

record ResultConvergenceConfig(
        long taskResultIdleIntervalMillis,
        long networkEvidenceIdleIntervalMillis
) {

    static final int TASK_RESULT_BATCH_LIMIT = 100;
    static final int GLOBAL_MAX_CONCURRENCY = 10;
    static final int TASK_SUCCESS_TARGET_CONCURRENCY = 6;
    static final int TASK_SUCCESS_MAX_CONCURRENCY = 10;
    static final int TASK_FAILURE_TARGET_CONCURRENCY = 3;
    static final int TASK_FAILURE_MAX_CONCURRENCY = 10;
    static final int NETWORK_EVIDENCE_TARGET_CONCURRENCY = 1;
    static final int NETWORK_EVIDENCE_MAX_CONCURRENCY = 1;
    static final long DEFAULT_IDLE_INTERVAL_MILLIS = 100;

    ResultConvergenceConfig {
        if (taskResultIdleIntervalMillis < 1) {
            throw new IllegalArgumentException(
                    "taskResultIdleIntervalMillis must be positive"
            );
        }
        if (networkEvidenceIdleIntervalMillis < 1) {
            throw new IllegalArgumentException(
                    "networkEvidenceIdleIntervalMillis must be positive"
            );
        }
    }

    static ResultConvergenceConfig defaults() {
        return new ResultConvergenceConfig(
                DEFAULT_IDLE_INTERVAL_MILLIS,
                DEFAULT_IDLE_INTERVAL_MILLIS
        );
    }
}
