package com.xa.mass.scenario.messages;

/** Business rejection or uncertainty, with the existing HTTP correlation evidence. */
final class MessageError extends RuntimeException {
    final int status;
    final String taskId;
    Long confirmedAddedCount, existingCount;

    MessageError(int status, String message, String taskId) {
        super(message); this.status = status; this.taskId = taskId;
    }
}
