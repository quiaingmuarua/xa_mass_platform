package com.xa.mass.runtime.worker;

public enum WorkerAdmissionStatus {
    ACCEPTED,
    MISSING_SLOT,
    REMOVING_SLOT,
    STALE_HEARTBEAT,
    DISPATCH_DISABLED,
    CAPACITY_UNAVAILABLE,
    GROUP_MISMATCH,
    ADAPTER_NODE_MISMATCH;

    public static WorkerAdmissionStatus fromReserveStatus(ReserveStatus status) {
        if (status == null) {
            return CAPACITY_UNAVAILABLE;
        }
        return WorkerAdmissionStatus.valueOf(status.name());
    }
}
