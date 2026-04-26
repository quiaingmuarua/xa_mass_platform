package com.xa.mass.engine.work;

public enum WorkEnqueueStatus {
    ENQUEUED,
    DUPLICATE,
    INVALID_ITEM,
    TASK_NOT_ACCEPTING,
    BACKPRESSURE_REJECTED,
    STORE_UNAVAILABLE,
    FAILED
}
