package com.xa.mass.runtime.worker.slot;

public enum WorkerScoreBandTransitionType {
    RECOVERABLE_NEGATIVE,
    PARK,
    OWNER_VALIDATED_RECOVERY,
    FUTURE_INTERVAL,
    CLAIM_CLOSE,
    ATTEMPT_TIMEOUT
}
