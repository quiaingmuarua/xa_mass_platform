package com.xa.mass.worker.runtime.admission;

import com.xa.mass.runtime.worker.ReserveStatus;

public enum WorkerAdmissionStatus {
    ACCEPTED,
    MISSING_SLOT,
    REMOVING_SLOT,
    STALE_HEARTBEAT,
    DISPATCH_DISABLED,
    CAPACITY_UNAVAILABLE,
    GROUP_MISMATCH;

    public static WorkerAdmissionStatus fromReserveStatus(ReserveStatus status) {
        if (status == null) {
            return CAPACITY_UNAVAILABLE;
        }
        return WorkerAdmissionStatus.valueOf(status.name());
    }
}
