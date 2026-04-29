package com.xa.mass.runtime.api;

public enum WorkEnqueueStatus {
    ENQUEUED,
    DUPLICATE,
    INVALID_ITEM,
    TASK_NOT_ACCEPTING,
    BACKPRESSURE_REJECTED,
    STORE_UNAVAILABLE,
    FAILED
}

