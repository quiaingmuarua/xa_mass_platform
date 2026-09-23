package com.xa.mass.kernel.worker;

import java.util.Objects;

/**
 * Opaque correlation evidence for one Worker lease.
 *
 * <p>Result policy may group and forward this reference, but only Worker-owned
 * event mechanisms in this package can recover the underlying score.</p>
 */
public final class WorkerLeaseReference {

    private final long encodedScore;

    private WorkerLeaseReference(long encodedScore) {
        if (encodedScore <= 0) {
            throw new IllegalArgumentException(
                    "encodedScore must be positive"
            );
        }
        this.encodedScore = encodedScore;
    }

    public static WorkerLeaseReference fromEncodedScore(long encodedScore) {
        return new WorkerLeaseReference(encodedScore);
    }

    long encodedScore() {
        return encodedScore;
    }

    @Override
    public boolean equals(Object value) {
        return this == value
                || value instanceof WorkerLeaseReference other
                && encodedScore == other.encodedScore;
    }

    @Override
    public int hashCode() {
        return Objects.hash(encodedScore);
    }

    @Override
    public String toString() {
        return "WorkerLeaseReference[opaque]";
    }
}
