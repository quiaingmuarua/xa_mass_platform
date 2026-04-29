package com.xa.mass.runtime.queue;

public enum KeyedQueuePollStatus {
    DELIVERED,
    EMPTY,
    INVALID_REQUEST,
    UNAVAILABLE,
    SHUTDOWN
}
