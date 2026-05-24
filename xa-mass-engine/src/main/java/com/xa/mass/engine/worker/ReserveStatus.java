package com.xa.mass.engine.worker;

public enum ReserveStatus {
    ACCEPTED,
    MISSING_SLOT,
    REMOVING_SLOT,
    STALE_HEARTBEAT,
    DISPATCH_DISABLED,
    CAPACITY_UNAVAILABLE,
    GROUP_MISMATCH,
    ADAPTER_NODE_MISMATCH
}
