package com.xa.mass.runtime.worker.slot;

public record WorkerScoreBandTransitionResult(
        WorkerScoreBandTransitionStatus status,
        WorkerScoreBandSlot before,
        WorkerScoreBandSlot after,
        String reason
) {

    public WorkerScoreBandTransitionResult {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        reason = reason == null || reason.isBlank() ? null : reason.trim();
    }

    public static WorkerScoreBandTransitionResult accepted(WorkerScoreBandSlot before,
                                                           WorkerScoreBandSlot after) {
        return new WorkerScoreBandTransitionResult(
                WorkerScoreBandTransitionStatus.ACCEPTED,
                before,
                after,
                null
        );
    }

    public static WorkerScoreBandTransitionResult rejected(WorkerScoreBandTransitionStatus status,
                                                           WorkerScoreBandSlot before,
                                                           String reason) {
        if (status == WorkerScoreBandTransitionStatus.ACCEPTED) {
            throw new IllegalArgumentException("accepted result requires accepted(before, after)");
        }
        return new WorkerScoreBandTransitionResult(status, before, before, reason);
    }

    public boolean accepted() {
        return status == WorkerScoreBandTransitionStatus.ACCEPTED;
    }
}
