package com.xa.mass.runtime.worker.slot;

public enum WorkerScoreBandTransitionStatus {
    ACCEPTED,
    MISSING_SLOT,
    STALE_OBSERVATION,
    INVALID_TRANSITION
}
