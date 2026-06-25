package com.xa.mass.runtime.worker.slot;

public record WorkerScoreBandSlot(
        WorkerScoreBandSlotMetadata metadata,
        long score
) {

    public WorkerScoreBandSlot {
        if (metadata == null) {
            throw new IllegalArgumentException("metadata must not be null");
        }
    }

    public String homeBucketId() {
        return metadata.homeBucketId();
    }

    public String workerGroupId() {
        return metadata.workerGroupId();
    }

    public String workerId() {
        return metadata.workerId();
    }

    public WorkerScoreBandKind band(long nowMillis) {
        return WorkerScoreBand.classify(score, nowMillis);
    }
}
