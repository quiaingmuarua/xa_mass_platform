package com.xa.mass.engine;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Visitor for one bounded compatibility task-message projection.
 *
 * <p>Outer modules should assemble their own DTOs from this callback instead of
 * importing engine-owned compatibility message view models.</p>
 */
@CompatibilityProjectionOnly
@FunctionalInterface
public interface TaskCompatibilityMessageVisitor {

    void onMessage(String messageId,
                   String taskId,
                   String status,
                   String latestAttemptId,
                   String latestAttemptWorkerId,
                   String latestAttemptWorkerContextId,
                   String latestAttemptBatchId,
                   int retryCount,
                   int maxRetryCount,
                   String errorMessage,
                   String errorCode,
                   String finalReason,
                   String payloadRef,
                   Map<String, Object> input,
                   Map<String, Object> output,
                   LocalDateTime assignedTime,
                   LocalDateTime createTime,
                   LocalDateTime updateTime,
                   LocalDateTime startTime,
                   LocalDateTime completeTime);
}
