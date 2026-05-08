package com.xa.mass.engine;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;
import com.xa.mass.runtime.api.TaskWorkEnvelope;
import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.api.projection.TaskMessageProjectionFinalReason;
import com.xa.mass.storage.api.projection.TaskMessageProjectionStatus;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Engine-local residue shape for logical message compatibility projection.
 *
 * <p>This keeps storage projection types from leaking through engine logic
 * that still has to work with compatibility residue during migration.</p>
 */
@CompatibilityProjectionOnly
record CompatibilityMessageProjection(String messageId,
                                      String taskId,
                                      Map<String, Object> input,
                                      String payloadRef,
                                      TaskMessageProjectionStatus status,
                                      LocalDateTime assignedTime,
                                      LocalDateTime createTime,
                                      LocalDateTime updateTime,
                                      LocalDateTime startTime,
                                      LocalDateTime completeTime,
                                      int retryCount,
                                      int maxRetryCount,
                                      String errorMessage,
                                      String errorCode,
                                      TaskMessageProjectionFinalReason finalReason,
                                      Map<String, Object> output,
                                      String latestAttemptId,
                                      String latestAttemptWorkerId,
                                      String latestAttemptWorkerContextId,
                                      String latestAttemptBatchId) {

    CompatibilityMessageProjection {
        input = copyMap(input);
        output = copyMap(output);
        retryCount = Math.max(0, retryCount);
        maxRetryCount = Math.max(0, maxRetryCount);
    }

    static CompatibilityMessageProjection fromStorage(TaskDetailStore.TaskMessageProjection projection) {
        if (projection == null) {
            return null;
        }
        return new CompatibilityMessageProjection(
                projection.messageId(),
                projection.taskId(),
                projection.input(),
                projection.payloadRef(),
                projection.status(),
                projection.assignedTime(),
                projection.createTime(),
                projection.updateTime(),
                projection.startTime(),
                projection.completeTime(),
                projection.retryCount(),
                projection.maxRetryCount(),
                projection.errorMessage(),
                projection.errorCode(),
                projection.finalReason(),
                projection.output(),
                projection.latestAttemptId(),
                projection.latestAttemptWorkerId(),
                projection.latestAttemptWorkerContextId(),
                projection.latestAttemptBatchId()
        );
    }

    static CompatibilityMessageProjection fromRuntimeWork(TaskWorkEnvelope runtimeWork) {
        if (runtimeWork == null) {
            return null;
        }
        LocalDateTime createdAt = runtimeWork.createdAt() == null
                ? null
                : LocalDateTime.ofInstant(runtimeWork.createdAt(), java.time.ZoneId.systemDefault());
        return new CompatibilityMessageProjection(
                runtimeWork.messageId(),
                runtimeWork.taskId(),
                Map.of(),
                runtimeWork.payloadRef(),
                TaskMessageProjectionStatus.INIT,
                null,
                createdAt,
                createdAt,
                null,
                null,
                runtimeWork.retryCount(),
                runtimeWork.maxRetryCount(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    TaskDetailStore.TaskMessageProjection toStorageProjection() {
        return new TaskDetailStore.TaskMessageProjection(
                messageId,
                taskId,
                input,
                payloadRef,
                status,
                assignedTime,
                createTime,
                updateTime,
                startTime,
                completeTime,
                retryCount,
                maxRetryCount,
                errorMessage,
                errorCode,
                finalReason,
                output,
                latestAttemptId,
                latestAttemptWorkerId,
                latestAttemptWorkerContextId,
                latestAttemptBatchId
        );
    }

    boolean isCompleted() {
        return status != null && status.isFinal();
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        if (source.isEmpty()) {
            return Map.of();
        }
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
