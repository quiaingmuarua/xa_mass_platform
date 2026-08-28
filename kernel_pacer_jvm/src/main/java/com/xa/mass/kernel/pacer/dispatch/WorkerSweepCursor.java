package com.xa.mass.kernel.pacer.dispatch;

import java.util.Objects;

/** Opaque exclusive upper bound for one Worker score sweep. */
final class WorkerSweepCursor {

    private static final WorkerSweepCursor START = new WorkerSweepCursor(0);

    private final long encodedScore;

    private WorkerSweepCursor(long encodedScore) {
        this.encodedScore = encodedScore;
    }

    public static WorkerSweepCursor start() {
        return START;
    }

    static WorkerSweepCursor fromEncodedScore(long encodedScore) {
        return encodedScore == 0
                ? START
                : new WorkerSweepCursor(encodedScore);
    }

    long encodedScore() {
        return encodedScore;
    }

    @Override
    public boolean equals(Object value) {
        return this == value
                || value instanceof WorkerSweepCursor other
                && encodedScore == other.encodedScore;
    }

    @Override
    public int hashCode() {
        return Objects.hash(encodedScore);
    }

    @Override
    public String toString() {
        return "WorkerSweepCursor[opaque]";
    }
}
