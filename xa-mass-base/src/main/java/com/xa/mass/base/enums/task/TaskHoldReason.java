package com.xa.mass.base.enums.task;

/**
 * Reason why a task is currently held in BLOCKED state.
 */
public enum TaskHoldReason {
    REVIEW_REJECTED,
    MANUAL_BLOCKED,
    POLICY_BLOCKED,
    UPSTREAM_DEPENDENCY_BLOCKED
}
