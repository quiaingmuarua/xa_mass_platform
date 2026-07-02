package com.xa.mass.task.runtime;

import java.util.List;

public interface TaskRuntimeWorkPort {

    AppendBatchOutcome appendBacklog(String taskId, List<AppendItemInput> frames, int maxBatchSize);

    ClaimReadyOutcome claimBacklog(ScoreCandidate candidate,
                                   List<WorkerReservationEvidence> reservations,
                                   int maxItems,
                                   long leaseMillis,
                                   long nowMillis);
}
