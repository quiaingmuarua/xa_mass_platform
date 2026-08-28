package com.xa.mass.kernel.pacer.dispatch;

import java.util.Objects;

/** Opaque exact TaskItem score evidence for one dispatch observation. */
final class TaskItemReference {

    private final String taskId;
    private final String messageId;
    private final long encodedScore;

    TaskItemReference(String taskId, String messageId, long encodedScore) {
        if (taskId == null || taskId.isBlank()
                || messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException(
                    "TaskItem reference identities must be non-blank"
            );
        }
        this.taskId = taskId;
        this.messageId = messageId;
        this.encodedScore = encodedScore;
    }

    String taskId() {
        return taskId;
    }

    String messageId() {
        return messageId;
    }

    long encodedScore() {
        return encodedScore;
    }

    @Override
    public boolean equals(Object value) {
        return this == value
                || value instanceof TaskItemReference other
                && encodedScore == other.encodedScore
                && taskId.equals(other.taskId)
                && messageId.equals(other.messageId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(taskId, messageId, encodedScore);
    }

    @Override
    public String toString() {
        return "TaskItemReference[opaque]";
    }
}
