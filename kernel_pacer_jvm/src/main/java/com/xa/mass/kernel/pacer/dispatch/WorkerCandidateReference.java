package com.xa.mass.kernel.pacer.dispatch;

import java.util.Objects;

/** Opaque exact Worker score evidence for Dispatch mechanisms. */
final class WorkerCandidateReference {

    private final String workerGroupId;
    private final String workerId;
    private final long encodedScore;

    WorkerCandidateReference(
            String workerGroupId,
            String workerId,
            long encodedScore
    ) {
        if (workerGroupId == null || workerGroupId.isBlank()
                || workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException(
                    "Worker reference identities must be non-blank"
            );
        }
        this.workerGroupId = workerGroupId;
        this.workerId = workerId;
        this.encodedScore = encodedScore;
    }

    public String workerGroupId() {
        return workerGroupId;
    }

    public String workerId() {
        return workerId;
    }

    long encodedScore() {
        return encodedScore;
    }

    @Override
    public boolean equals(Object value) {
        return this == value
                || value instanceof WorkerCandidateReference other
                && encodedScore == other.encodedScore
                && workerGroupId.equals(other.workerGroupId)
                && workerId.equals(other.workerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workerGroupId, workerId, encodedScore);
    }

    @Override
    public String toString() {
        return "WorkerCandidateReference[opaque]";
    }
}
