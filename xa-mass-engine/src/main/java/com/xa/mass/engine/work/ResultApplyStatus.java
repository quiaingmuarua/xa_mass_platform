package com.xa.mass.engine.work;

public enum ResultApplyStatus {
    SUCCESS_APPLIED,
    FAILURE_FINALIZED,
    RETRY_SCHEDULED,
    DUPLICATE_OR_LATE,
    STALE_LEASE,
    NO_ACTIVE_LEASE,
    TASK_TERMINAL,
    INVALID_ITEM,
    FAILED
}
