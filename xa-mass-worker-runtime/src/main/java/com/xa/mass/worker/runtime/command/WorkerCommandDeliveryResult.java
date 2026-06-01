package com.xa.mass.worker.runtime.command;

public record WorkerCommandDeliveryResult(
        WorkerCommandDeliveryStatus status,
        String reason
) {

    public WorkerCommandDeliveryResult {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        reason = reason == null || reason.isBlank() ? status.defaultReason() : reason.trim();
    }

    public static WorkerCommandDeliveryResult accepted(String reason) {
        return new WorkerCommandDeliveryResult(WorkerCommandDeliveryStatus.ACCEPTED, reason);
    }

    public static WorkerCommandDeliveryResult deferred(String reason) {
        return new WorkerCommandDeliveryResult(WorkerCommandDeliveryStatus.DEFERRED, reason);
    }

    public static WorkerCommandDeliveryResult workerUnavailable(String reason) {
        return new WorkerCommandDeliveryResult(WorkerCommandDeliveryStatus.WORKER_UNAVAILABLE, reason);
    }

    public static WorkerCommandDeliveryResult rejected(String reason) {
        return new WorkerCommandDeliveryResult(WorkerCommandDeliveryStatus.REJECTED, reason);
    }

    public static WorkerCommandDeliveryResult failed(String reason) {
        return new WorkerCommandDeliveryResult(WorkerCommandDeliveryStatus.FAILED, reason);
    }

    public boolean accepted() {
        return status == WorkerCommandDeliveryStatus.ACCEPTED;
    }

    public boolean deferred() {
        return status == WorkerCommandDeliveryStatus.DEFERRED;
    }

    public boolean workerUnavailable() {
        return status == WorkerCommandDeliveryStatus.WORKER_UNAVAILABLE;
    }
}
