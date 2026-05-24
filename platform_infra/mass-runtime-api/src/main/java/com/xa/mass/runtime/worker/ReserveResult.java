package com.xa.mass.runtime.worker;

public record ReserveResult(
        ReserveStatus status,
        WorkerSlot slot,
        String reason
) {

    public ReserveResult {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        reason = normalizeNullable(reason);
    }

    public static ReserveResult accepted(WorkerSlot slot) {
        return new ReserveResult(ReserveStatus.ACCEPTED, slot, null);
    }

    public static ReserveResult rejected(ReserveStatus status, String reason) {
        if (status == ReserveStatus.ACCEPTED) {
            throw new IllegalArgumentException("accepted result requires accepted(slot)");
        }
        return new ReserveResult(status, null, reason);
    }

    public boolean accepted() {
        return status == ReserveStatus.ACCEPTED;
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
