package com.xa.mass.runtime.worker.slot;

import java.util.List;
import java.util.Optional;

/**
 * Worker-runtime-owned score-band slot state machine.
 */
public interface WorkerScoreBandSlotRuntime {

    void upsert(WorkerScoreBandSlotMetadata metadata,
                long initialScore,
                String reasonCode,
                long observedAtMillis);

    Optional<WorkerScoreBandSlot> slot(String homeBucketId, String workerId);

    List<WorkerScoreBandSlot> acquire(WorkerScoreBandAcquireRequest request);

    WorkerScoreBandTransitionResult transition(WorkerScoreBandTransitionCommand command);

    void remove(String homeBucketId, String workerId, String reasonCode, long observedAtMillis);
}
