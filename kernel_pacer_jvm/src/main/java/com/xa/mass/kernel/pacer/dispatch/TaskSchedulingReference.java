package com.xa.mass.kernel.pacer.dispatch;

import java.util.Objects;

/** Opaque exact Task score evidence for one scheduling observation. */
final class TaskSchedulingReference {

    private final String taskId;
    private final long encodedScore;

    TaskSchedulingReference(String taskId, long encodedScore) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must be non-blank");
        }
        this.taskId = taskId;
        this.encodedScore = encodedScore;
    }

    String taskId() {
        return taskId;
    }

    long encodedScore() {
        return encodedScore;
    }

    @Override
    public boolean equals(Object value) {
        return this == value
                || value instanceof TaskSchedulingReference other
                && encodedScore == other.encodedScore
                && taskId.equals(other.taskId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(taskId, encodedScore);
    }

    @Override
    public String toString() {
        return "TaskSchedulingReference[opaque]";
    }
}
