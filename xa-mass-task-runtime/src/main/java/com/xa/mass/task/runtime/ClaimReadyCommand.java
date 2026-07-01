package com.xa.mass.task.runtime;

import java.util.List;

public record ClaimReadyCommand(
        String taskId,
        List<WorkerReservationEvidence> workerReservations,
        ClaimLeasePolicy leasePolicy
) {

    public ClaimReadyCommand {
        taskId = TaskRuntimeContractChecks.requireText(taskId, "taskId");
        workerReservations = TaskRuntimeContractChecks.copyNonEmpty(workerReservations, "workerReservations");
        if (leasePolicy == null) {
            throw new IllegalArgumentException("leasePolicy is required");
        }
    }
}
