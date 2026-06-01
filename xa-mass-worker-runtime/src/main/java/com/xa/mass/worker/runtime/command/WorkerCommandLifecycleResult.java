package com.xa.mass.worker.runtime.command;

public record WorkerCommandLifecycleResult(
        WorkerCommandLifecycleResultCode code,
        WorkerCommandRecord record,
        WorkerCommandStatus previousStatus,
        WorkerCommandStatus currentStatus,
        String reason
) {

    public boolean success() {
        return code == WorkerCommandLifecycleResultCode.ACCEPTED
                || code == WorkerCommandLifecycleResultCode.IDEMPOTENT;
    }
}
