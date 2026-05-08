package com.xa.mass.engine;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Visitor for one bounded compatibility task-message attempt projection.
 *
 * <p>Outer modules should assemble their own DTOs from this callback instead of
 * importing engine-owned compatibility attempt view models.</p>
 */
@CompatibilityProjectionOnly
@FunctionalInterface
public interface TaskCompatibilityMessageAttemptVisitor {

    void onAttempt(String attemptId,
                   String taskId,
                   String messageId,
                   int attemptNo,
                   String workerId,
                   String workerContextId,
                   String batchId,
                   String status,
                   LocalDateTime leaseExpireTime,
                   LocalDateTime dispatchTime,
                   LocalDateTime ackTime,
                   LocalDateTime startTime,
                   LocalDateTime finishTime,
                   String finalReason,
                   String errorMessage,
                   String errorCode,
                   Map<String, Object> output,
                   LocalDateTime createTime,
                   LocalDateTime updateTime);
}
