package com.xa.mass.worker.runtime.evidence;

/**
 * Diagnostic worker readiness dimension.
 *
 * <p>Readiness answers whether a reachable worker may participate in dispatch
 * in principle. It is derived from worker-runtime control evidence in the
 * current implementation and is not a second scheduling truth.</p>
 */
public enum WorkerReadinessState {
    READY,
    DRAINING,
    INIT_REQUIRED,
    VERSION_MISMATCH,
    ACCOUNT_UNAVAILABLE,
    HEALTH_UNAVAILABLE,
    MAINTENANCE;

    public boolean ready() {
        return this == READY;
    }

    public static WorkerReadinessState fromDispatchEvidence(boolean dispatchEnabled, boolean removing) {
        if (removing) {
            return DRAINING;
        }
        return dispatchEnabled ? READY : MAINTENANCE;
    }
}
