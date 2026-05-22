package com.xa.mass.engine;

import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionFinalReason;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionStatus;
import com.xa.mass.storage.api.projection.TaskMessageProjectionFinalReason;
import com.xa.mass.storage.api.projection.TaskMessageProjectionStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

final class ProjectionTestSupport {

    private ProjectionTestSupport() {
    }

    record MessageSnapshot(List<TaskDetailStore.TaskMessageProjection> messages, int limit, boolean truncated) {

        MessageSnapshot {
            messages = messages == null ? List.of() : List.copyOf(messages);
            limit = Math.max(0, limit);
        }
    }

    static TaskDetailStore.TaskMessageProjection resetToInit(TaskDetailStore.TaskMessageProjection projection) {
        return new TaskDetailStore.TaskMessageProjection(
                projection.messageId(),
                projection.taskId(),
                projection.input(),
                projection.payloadRef(),
                TaskMessageProjectionStatus.INIT,
                null,
                projection.createTime(),
                LocalDateTime.now(),
                null,
                null,
                projection.retryCount(),
                projection.maxRetryCount(),
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    static TaskDetailStore.TaskMessageProjection markAssigned(TaskDetailStore.TaskMessageProjection projection,
                                                              String attemptId,
                                                              String workerId,
                                                              String batchId) {
        LocalDateTime now = LocalDateTime.now();
        return new TaskDetailStore.TaskMessageProjection(
                projection.messageId(),
                projection.taskId(),
                projection.input(),
                projection.payloadRef(),
                TaskMessageProjectionStatus.ASSIGNED,
                projection.assignedTime() != null ? projection.assignedTime() : now,
                projection.createTime(),
                now,
                projection.startTime(),
                projection.completeTime(),
                projection.retryCount(),
                projection.maxRetryCount(),
                projection.errorMessage(),
                projection.errorCode(),
                projection.finalReason(),
                projection.output(),
                attemptId,
                workerId,
                batchId
        );
    }

    static TaskDetailStore.TaskMessageProjection markRunning(TaskDetailStore.TaskMessageProjection projection) {
        LocalDateTime now = LocalDateTime.now();
        return new TaskDetailStore.TaskMessageProjection(
                projection.messageId(),
                projection.taskId(),
                projection.input(),
                projection.payloadRef(),
                TaskMessageProjectionStatus.RUNNING,
                projection.assignedTime(),
                projection.createTime(),
                now,
                projection.startTime() != null ? projection.startTime() : now,
                projection.completeTime(),
                projection.retryCount(),
                projection.maxRetryCount(),
                projection.errorMessage(),
                projection.errorCode(),
                projection.finalReason(),
                projection.output(),
                projection.latestAttemptId(),
                projection.latestAttemptWorkerId(),
                projection.latestAttemptBatchId()
        );
    }

    static TaskDetailStore.TaskMessageProjection markSuccess(TaskDetailStore.TaskMessageProjection projection,
                                                             Map<String, Object> output,
                                                             TaskMessageProjectionFinalReason finalReason) {
        LocalDateTime now = LocalDateTime.now();
        return new TaskDetailStore.TaskMessageProjection(
                projection.messageId(),
                projection.taskId(),
                projection.input(),
                projection.payloadRef(),
                TaskMessageProjectionStatus.SUCCESS,
                projection.assignedTime(),
                projection.createTime(),
                now,
                projection.startTime(),
                now,
                projection.retryCount(),
                projection.maxRetryCount(),
                null,
                null,
                finalReason,
                output,
                projection.latestAttemptId(),
                projection.latestAttemptWorkerId(),
                projection.latestAttemptBatchId()
        );
    }

    static TaskDetailStore.TaskMessageProjection markExpired(TaskDetailStore.TaskMessageProjection projection,
                                                             TaskMessageProjectionFinalReason finalReason,
                                                             String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        return new TaskDetailStore.TaskMessageProjection(
                projection.messageId(),
                projection.taskId(),
                projection.input(),
                projection.payloadRef(),
                TaskMessageProjectionStatus.EXPIRED,
                projection.assignedTime(),
                projection.createTime(),
                now,
                projection.startTime(),
                now,
                projection.retryCount(),
                projection.maxRetryCount(),
                errorMessage,
                projection.errorCode(),
                finalReason,
                null,
                projection.latestAttemptId(),
                projection.latestAttemptWorkerId(),
                projection.latestAttemptBatchId()
        );
    }

    static TaskDetailStore.TaskMessageProjection forceFinal(TaskDetailStore.TaskMessageProjection projection,
                                                            TaskMessageProjectionStatus status,
                                                            TaskMessageProjectionFinalReason finalReason,
                                                            String errorMessage,
                                                            String errorCode,
                                                            Map<String, Object> output) {
        LocalDateTime now = LocalDateTime.now();
        return new TaskDetailStore.TaskMessageProjection(
                projection.messageId(),
                projection.taskId(),
                projection.input(),
                projection.payloadRef(),
                status,
                projection.assignedTime(),
                projection.createTime(),
                now,
                status == TaskMessageProjectionStatus.RUNNING && projection.startTime() == null ? now : projection.startTime(),
                status != null && status.isFinal() ? now : projection.completeTime(),
                projection.retryCount(),
                projection.maxRetryCount(),
                errorMessage,
                errorCode,
                finalReason,
                output,
                projection.latestAttemptId(),
                projection.latestAttemptWorkerId(),
                projection.latestAttemptBatchId()
        );
    }

    static TaskDetailStore.TaskMessageProjection withMaxRetryCount(TaskDetailStore.TaskMessageProjection projection,
                                                                   int maxRetryCount) {
        return new TaskDetailStore.TaskMessageProjection(
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
                maxRetryCount,
                projection.errorMessage(),
                projection.errorCode(),
                projection.finalReason(),
                projection.output(),
                projection.latestAttemptId(),
                projection.latestAttemptWorkerId(),
                projection.latestAttemptBatchId()
        );
    }

    static TaskDetailStore.TaskMessageAttemptProjection attempt(String attemptId,
                                                                String taskId,
                                                                String messageId,
                                                                int attemptNo,
                                                                String workerId,
                                                                String batchId,
                                                                TaskMessageAttemptProjectionStatus status) {
        return new TaskDetailStore.TaskMessageAttemptProjection(
                attemptId,
                taskId,
                messageId,
                attemptNo,
                workerId,
                batchId,
                status,
                null,
                null,
                null,
                null
        );
    }

    static TaskDetailStore.TaskMessageAttemptProjection withAttemptStatus(TaskDetailStore.TaskMessageAttemptProjection projection,
                                                                          TaskMessageAttemptProjectionStatus status,
                                                                          TaskMessageAttemptProjectionFinalReason finalReason,
                                                                          String errorMessage,
                                                                          String errorCode,
                                                                          Map<String, Object> output) {
        return new TaskDetailStore.TaskMessageAttemptProjection(
                projection.attemptId(),
                projection.taskId(),
                projection.messageId(),
                projection.attemptNo(),
                projection.workerId(),
                projection.batchId(),
                status,
                finalReason,
                errorMessage,
                errorCode,
                output
        );
    }
}
