package com.xa.mass.engine;

import com.xa.mass.task.runtime.ClaimLeasePolicy;
import com.xa.mass.task.runtime.ClaimReadyOutcome;
import com.xa.mass.task.runtime.ClaimedWorkItem;
import com.xa.mass.task.runtime.RuntimeEpoch;
import com.xa.mass.task.runtime.WorkerReservationEvidence;

import java.util.List;

final class TaskRuntimeClaimTestSupport {

    private TaskRuntimeClaimTestSupport() {
    }

    static ClaimReadyOutcome claim(TaskRuntimeServingLane lane,
                                   String taskId,
                                   String workerGroupId,
                                   String workerId,
                                   String batchId,
                                   int maxItems,
                                   long leaseSeconds) {
        return claim(
                lane,
                taskId,
                workerGroupId,
                workerId,
                batchId,
                workerId + ":" + batchId,
                null,
                maxItems,
                leaseSeconds);
    }

    static ClaimReadyOutcome claim(TaskRuntimeServingLane lane,
                                   String taskId,
                                   String workerGroupId,
                                   String workerId,
                                   String batchId,
                                   String reservationToken,
                                   Long scoreBandClaimScore,
                                   int maxItems,
                                   long leaseSeconds) {
        return lane.claimReady(
                taskId,
                List.of(new WorkerReservationEvidence(
                        workerId,
                        workerGroupId,
                        reservationToken,
                        null,
                        batchId,
                        scoreBandClaimScore)),
                new ClaimLeasePolicy(
                        Math.max(1, maxItems),
                        Math.max(1L, leaseSeconds) * 1_000L,
                        1L,
                        RuntimeEpoch.of(taskId, 1L)));
    }

    static ClaimedWorkItem claimSingle(TaskRuntimeServingLane lane,
                                       long leaseSeconds,
                                       String taskId,
                                       String workerGroupId,
                                       String workerId,
                                       String batchId) {
        var claimed = claim(
                        lane,
                        taskId,
                        workerGroupId,
                        workerId,
                        batchId,
                        1,
                        leaseSeconds)
                .claimedItems();
        if (claimed.isEmpty()) {
            throw new AssertionError("expected one claimed work item for task " + taskId);
        }
        return claimed.getFirst();
    }
}
