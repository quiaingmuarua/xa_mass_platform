package com.xa.mass.api.review;

import com.xa.mass.sdk.model.TaskWorkAttemptClosedNotification;
import com.xa.mass.sdk.model.TaskWorkAttemptClosedSnapshot;

/**
 * Immutable review event for one concrete attempt reaching a final state.
 */
public record TaskReviewAttemptClosedEvent(String taskId,
                                           String messageId,
                                           String attemptId,
                                           int attemptNo,
                                           String workerId,
                                           String batchId,
                                           String status,
                                           String finalReason)
        implements TaskReviewReportEvent {

    public TaskReviewAttemptClosedEvent {
        attemptNo = Math.max(0, attemptNo);
    }

    public static TaskReviewAttemptClosedEvent from(TaskWorkAttemptClosedNotification notification) {
        return from(notification == null ? null : notification.attemptSnapshot());
    }

    public static TaskReviewAttemptClosedEvent from(TaskWorkAttemptClosedSnapshot snapshot) {
        if (snapshot == null) {
            return new TaskReviewAttemptClosedEvent(null, null, null, 0, null, null, null, null);
        }
        return new TaskReviewAttemptClosedEvent(
                snapshot.taskId(),
                snapshot.messageId(),
                snapshot.attemptId(),
                snapshot.attemptNo(),
                snapshot.workerId(),
                snapshot.batchId(),
                snapshot.status(),
                snapshot.finalReason()
        );
    }
}
