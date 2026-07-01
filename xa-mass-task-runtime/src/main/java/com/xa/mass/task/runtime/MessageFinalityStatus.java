package com.xa.mass.task.runtime;

public enum MessageFinalityStatus {
    ATTEMPT_CLOSED,
    RETRY_SCHEDULED,
    LOGICAL_FINAL,
    DUPLICATE_OR_LATE,
    REJECTED
}
