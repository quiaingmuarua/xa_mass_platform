package com.xa.mass.task.runtime;

public record WorkerReservationEvidence(
        String workerId,
        String workerGroupId,
        String reservationToken,
        String dispatchTargetRef,
        String batchId,
        Long scoreBandClaimScore
) {

    public WorkerReservationEvidence(String workerId,
                                     String workerGroupId,
                                     String reservationToken,
                                     String dispatchTargetRef) {
        this(workerId, workerGroupId, reservationToken, dispatchTargetRef, null, null);
    }

    public WorkerReservationEvidence(String workerId,
                                     String workerGroupId,
                                     String reservationToken,
                                     String dispatchTargetRef,
                                     String batchId) {
        this(workerId, workerGroupId, reservationToken, dispatchTargetRef, batchId, null);
    }

    public WorkerReservationEvidence {
        workerId = TaskRuntimeContractChecks.requireText(workerId, "workerId");
        workerGroupId = TaskRuntimeContractChecks.requireText(workerGroupId, "workerGroupId");
        reservationToken = TaskRuntimeContractChecks.requireText(reservationToken, "reservationToken");
        dispatchTargetRef = dispatchTargetRef == null || dispatchTargetRef.isBlank() ? null : dispatchTargetRef;
        batchId = TaskRuntimeContractChecks.optionalText(batchId);
    }
}
