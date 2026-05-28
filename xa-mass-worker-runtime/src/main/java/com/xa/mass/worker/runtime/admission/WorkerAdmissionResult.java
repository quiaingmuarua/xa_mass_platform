package com.xa.mass.worker.runtime.admission;

import com.xa.mass.runtime.worker.ReserveResult;

public record WorkerAdmissionResult(
        WorkerAdmissionStatus status,
        String reason
) {

    public WorkerAdmissionResult {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        reason = normalizeNullable(reason);
    }

    public static WorkerAdmissionResult acceptedResult() {
        return new WorkerAdmissionResult(WorkerAdmissionStatus.ACCEPTED, null);
    }

    public static WorkerAdmissionResult rejected(WorkerAdmissionStatus status, String reason) {
        if (status == WorkerAdmissionStatus.ACCEPTED) {
            throw new IllegalArgumentException("accepted result requires accepted()");
        }
        return new WorkerAdmissionResult(status, reason);
    }

    public static WorkerAdmissionResult fromReserveResult(ReserveResult result) {
        if (result == null) {
            return rejected(WorkerAdmissionStatus.CAPACITY_UNAVAILABLE, "worker reserve result missing");
        }
        if (result.accepted()) {
            return acceptedResult();
        }
        return rejected(WorkerAdmissionStatus.fromReserveStatus(result.status()), result.reason());
    }

    public boolean accepted() {
        return status == WorkerAdmissionStatus.ACCEPTED;
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
