package com.xa.mass.transport.model;

/**
 * Transport-level delivery outcome shared by concrete worker adapters.
 */
public enum DispatchOutcomeStatus {
    DELIVERED,
    QUEUED,
    NO_ENDPOINT,
    BACKPRESSURE,
    INVALID,
    UNAVAILABLE,
    FAILED,
    SHUTDOWN
}
