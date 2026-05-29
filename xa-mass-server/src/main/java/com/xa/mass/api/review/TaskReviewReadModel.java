package com.xa.mass.api.review;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Server-owned task review read model for console preview and export.
 *
 * <p>This is a host/read-model contract. It must not be treated as engine
 * scheduling, result convergence, or terminal-policy truth.</p>
 */
public interface TaskReviewReadModel {

    TaskReviewSnapshot loadReview(String taskId, int previewLimit);

    List<TaskReviewItem> loadItems(String taskId, int limit);

    List<TaskReviewAttempt> loadAttempts(String taskId, String messageId);

    TaskReviewStats loadStats(String taskId);

    record TaskReviewSnapshot(TaskReviewStats stats, List<TaskReviewItem> preview) {
        public TaskReviewSnapshot {
            stats = stats == null ? TaskReviewStats.empty() : stats;
            preview = preview == null ? List.of() : List.copyOf(preview);
        }
    }

    record TaskReviewStats(long totalItems,
                           long successItems,
                           long failedItems,
                           long expiredItems,
                           long processingItems) {

        public static TaskReviewStats empty() {
            return new TaskReviewStats(0L, 0L, 0L, 0L, 0L);
        }
    }

    record TaskReviewItem(String messageId,
                          String eventCode,
                          String status,
                          String finalReason,
                          String payloadRef,
                          int retryCount,
                          int maxRetryCount,
                          LocalDateTime createTime,
                          LocalDateTime assignedTime,
                          LocalDateTime startTime,
                          LocalDateTime completeTime,
                          LocalDateTime updateTime,
                          Map<String, Object> input,
                          String workerId,
                          String batchId,
                          String attemptId,
                          String errorCode,
                          String errorMessage,
                          Map<String, Object> output) {
    }

    record TaskReviewAttempt(String attemptId,
                             String taskId,
                             String messageId,
                             int attemptNo,
                             String workerId,
                             String batchId,
                             String status,
                             String finalReason,
                             String errorCode,
                             String errorMessage,
                             Map<String, Object> output) {
    }
}
