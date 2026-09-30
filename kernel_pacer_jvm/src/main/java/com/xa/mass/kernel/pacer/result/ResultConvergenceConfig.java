package com.xa.mass.kernel.pacer.result;

record ResultConvergenceConfig(
        long taskResultIdleMinMillis,
        long taskResultIdleStepMillis,
        long taskResultIdleMaxMillis,
        long networkEvidenceIdleIntervalMillis
) {

    static final int TASK_RESULT_BATCH_LIMIT = 100;
    static final int GLOBAL_MAX_CONCURRENCY = 10;
    static final int TASK_SUCCESS_TARGET_CONCURRENCY = 6;
    static final int TASK_SUCCESS_MAX_CONCURRENCY = 10;
    static final int TASK_FAILURE_TARGET_CONCURRENCY = 3;
    static final int TASK_FAILURE_MAX_CONCURRENCY = 10;
    static final int TASK_OBSERVATION_TARGET_CONCURRENCY = 1;
    static final int TASK_OBSERVATION_MAX_CONCURRENCY = 10;
    static final int NETWORK_EVIDENCE_TARGET_CONCURRENCY = 1;
    static final int NETWORK_EVIDENCE_MAX_CONCURRENCY = 1;
    static final long DEFAULT_IDLE_INTERVAL_MILLIS = 100;
    static final long DEFAULT_TASK_IDLE_MIN_MILLIS = 10;
    static final long DEFAULT_TASK_IDLE_STEP_MILLIS = 10;

    ResultConvergenceConfig {
        if (taskResultIdleMinMillis < 1) {
            throw new IllegalArgumentException(
                    "taskResultIdleMinMillis must be positive"
            );
        }
        if (taskResultIdleStepMillis < 0) {
            throw new IllegalArgumentException(
                    "taskResultIdleStepMillis must not be negative"
            );
        }
        if (taskResultIdleMaxMillis < taskResultIdleMinMillis) {
            throw new IllegalArgumentException(
                    "taskResultIdleMaxMillis must not be below taskResultIdleMinMillis"
            );
        }
        if (networkEvidenceIdleIntervalMillis < 1) {
            throw new IllegalArgumentException(
                    "networkEvidenceIdleIntervalMillis must be positive"
            );
        }
    }

    /** TASK lanes ramp 10ms..100ms while empty; Network Evidence keeps its fixed interval. */
    static ResultConvergenceConfig defaults() {
        return new ResultConvergenceConfig(
                DEFAULT_TASK_IDLE_MIN_MILLIS,
                DEFAULT_TASK_IDLE_STEP_MILLIS,
                DEFAULT_IDLE_INTERVAL_MILLIS,
                DEFAULT_IDLE_INTERVAL_MILLIS
        );
    }

    /** Fixed intervals for both lane kinds, as used by the Lab and boundary presets. */
    static ResultConvergenceConfig fixed(long taskResultIdleMillis, long networkEvidenceIdleMillis) {
        return new ResultConvergenceConfig(
                taskResultIdleMillis,
                0,
                taskResultIdleMillis,
                networkEvidenceIdleMillis
        );
    }
}
