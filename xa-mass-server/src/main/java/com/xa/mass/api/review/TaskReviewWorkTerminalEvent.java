package com.xa.mass.api.review;

import com.xa.mass.sdk.model.TaskWorkFinalNotification;
import com.xa.mass.sdk.model.TaskWorkFinalSnapshot;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable snapshot of one task work item reaching stable finality.
 */
public record TaskReviewWorkTerminalEvent(String taskId,
                                          String messageId,
                                          String status,
                                          String finalReason,
                                          int retryCount,
                                          int maxRetryCount,
                                          String eventCode,
                                          String workerId,
                                          String batchId,
                                          String attemptId,
                                          String errorCode,
                                          String errorMessage,
                                          String payloadRef,
                                          Instant createTime,
                                          Instant assignedTime,
                                          Instant startTime,
                                          Instant completeTime,
                                          Instant updateTime,
                                          Map<String, Object> output)
        implements TaskReviewReportEvent {

    public TaskReviewWorkTerminalEvent {
        retryCount = Math.max(0, retryCount);
        maxRetryCount = Math.max(0, maxRetryCount);
        output = output == null || output.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(output));
    }

    public static TaskReviewWorkTerminalEvent from(TaskWorkFinalNotification notification) {
        return from(notification == null ? null : notification.finalSnapshot());
    }

    public static TaskReviewWorkTerminalEvent from(TaskWorkFinalSnapshot snapshot) {
        if (snapshot == null) {
            return new TaskReviewWorkTerminalEvent(
                    null, null, null, null, 0, 0, null, null, null, null,
                    null, null, null, null, null, null, null, null, Map.of());
        }
        return new TaskReviewWorkTerminalEvent(
                snapshot.taskId(),
                snapshot.messageId(),
                snapshot.status(),
                snapshot.finalReason(),
                snapshot.retryCount(),
                snapshot.maxRetryCount(),
                snapshot.eventCode(),
                snapshot.workerId(),
                snapshot.batchId(),
                snapshot.attemptId(),
                snapshot.errorCode(),
                snapshot.errorMessage(),
                snapshot.payloadRef(),
                snapshot.createTime(),
                snapshot.assignedTime(),
                snapshot.startTime(),
                snapshot.completeTime(),
                snapshot.updateTime(),
                snapshot.output()
        );
    }
}
