package com.xa.mass.engine.worker;

public record WorkerStateProjectionResult(
        WorkerStateProjectionStatus status,
        String workerId,
        long stateVersion,
        boolean projectionChanged,
        WorkerStateProjection projection,
        String reason
) {

    public boolean success() {
        return status == WorkerStateProjectionStatus.ACCEPTED
                || status == WorkerStateProjectionStatus.IDEMPOTENT;
    }
}
