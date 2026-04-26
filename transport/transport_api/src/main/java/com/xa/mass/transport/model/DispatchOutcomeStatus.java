package com.xa.mass.transport.model;

/**
 * Transport-level dispatch outcome shared by concrete worker adapters.
 */
public enum DispatchOutcomeStatus {
    SENT,
    QUEUED,
    ENDPOINT_OFFLINE,
    BACKPRESSURE_REJECTED,
    INVALID_ITEM,
    ADAPTER_UNAVAILABLE,
    FAILED
}
