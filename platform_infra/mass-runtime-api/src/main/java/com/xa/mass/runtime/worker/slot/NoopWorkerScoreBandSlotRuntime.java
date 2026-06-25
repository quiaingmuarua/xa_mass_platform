package com.xa.mass.runtime.worker.slot;

import java.util.List;
import java.util.Optional;

/**
 * No-op score-band runtime used only when assembly has not provided storage.
 */
public enum NoopWorkerScoreBandSlotRuntime implements WorkerScoreBandSlotRuntime {
    INSTANCE;

    @Override
    public void upsert(WorkerScoreBandSlotMetadata metadata,
                       long initialScore,
                       String reasonCode,
                       long observedAtMillis) {
    }

    @Override
    public Optional<WorkerScoreBandSlot> slot(String homeBucketId, String workerId) {
        return Optional.empty();
    }

    @Override
    public List<WorkerScoreBandSlot> acquire(WorkerScoreBandAcquireRequest request) {
        return List.of();
    }

    @Override
    public WorkerScoreBandTransitionResult transition(WorkerScoreBandTransitionCommand command) {
        return WorkerScoreBandTransitionResult.rejected(
                WorkerScoreBandTransitionStatus.MISSING_SLOT,
                null,
                "score-band runtime not configured"
        );
    }

    @Override
    public void remove(String homeBucketId, String workerId, String reasonCode, long observedAtMillis) {
    }
}
