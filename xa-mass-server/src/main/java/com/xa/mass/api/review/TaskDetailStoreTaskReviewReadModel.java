package com.xa.mass.api.review;

import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.sdk.model.TaskItemBatchAppendReceipt;
import com.xa.mass.sdk.model.TaskWorkFinalNotification;
import com.xa.mass.sdk.model.TaskWorkFinalSnapshot;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionFinalReason;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionStatus;
import com.xa.mass.storage.api.projection.TaskMessageProjectionFinalReason;
import com.xa.mass.storage.api.projection.TaskMessageProjectionStatus;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Transitional server read-model implementation backed by the existing
 * bounded task-detail projection store.
 */
public class TaskDetailStoreTaskReviewReadModel implements TaskReviewReadModel, TaskReviewReadModelWriter {

    private final TaskDetailStore taskDetailStore;

    public TaskDetailStoreTaskReviewReadModel(TaskDetailStore taskDetailStore) {
        this.taskDetailStore = Objects.requireNonNull(taskDetailStore, "taskDetailStore");
    }

    @Override
    public TaskReviewSnapshot loadReview(String taskId, int previewLimit) {
        TaskReviewStats stats = loadStats(taskId);
        List<TaskReviewItem> preview = loadItems(taskId, previewLimit);
        return new TaskReviewSnapshot(stats, preview);
    }

    @Override
    public List<TaskReviewItem> loadItems(String taskId, int limit) {
        return taskDetailStore.getTaskMessageProjections(taskId, Math.max(1, limit))
                .stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(TaskDetailStore.TaskMessageProjection::createTime,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::toReviewItem)
                .toList();
    }

    @Override
    public List<TaskReviewAttempt> loadAttempts(String taskId, String messageId) {
        return taskDetailStore.getTaskMessageAttemptProjections(taskId, messageId)
                .stream()
                .filter(Objects::nonNull)
                .map(this::toReviewAttempt)
                .toList();
    }

    @Override
    public TaskReviewStats loadStats(String taskId) {
        TaskDetailStore.TaskMessageStats stats = taskDetailStore.getTaskMessageStats(taskId);
        if (stats == null) {
            return TaskReviewStats.empty();
        }
        return new TaskReviewStats(
                stats.getTotal(),
                stats.getSuccess(),
                stats.getFailed(),
                stats.getExpired(),
                stats.getProcessing()
        );
    }

    @Override
    public void recordItemsAccepted(String taskId,
                                    List<Map<String, Object>> acceptedItems,
                                    TaskItemBatchAppendReceipt receipt,
                                    int maxRetryCount) {
        if (taskId == null || taskId.isBlank() || acceptedItems == null || receipt == null) {
            return;
        }
        List<String> messageIds = receipt.messageIds();
        int itemCount = Math.min(acceptedItems.size(), messageIds.size());
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < itemCount; i++) {
            String messageId = messageIds.get(i);
            if (messageId == null || messageId.isBlank()) {
                continue;
            }
            taskDetailStore.upsertTaskMessageProjection(taskId, new TaskDetailStore.TaskMessageProjection(
                    messageId,
                    taskId,
                    acceptedItems.get(i),
                    null,
                    TaskMessageProjectionStatus.INIT,
                    null,
                    now,
                    now,
                    null,
                    null,
                    0,
                    Math.max(0, maxRetryCount),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            ));
        }
    }

    @Override
    public void recordWorkFinal(TaskWorkFinalNotification notification) {
        if (notification == null || notification.finalSnapshot() == null) {
            return;
        }
        TaskWorkFinalSnapshot snapshot = notification.finalSnapshot();
        String taskId = snapshot.taskId();
        String messageId = snapshot.messageId();
        if (taskId == null || taskId.isBlank() || messageId == null || messageId.isBlank()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        TaskDetailStore.TaskMessageProjection previous =
                taskDetailStore.getTaskMessageProjection(taskId, messageId).orElse(null);
        LocalDateTime createTime = firstNonNull(asLocalDateTime(snapshot.createTime()),
                previous != null ? previous.createTime() : null);
        LocalDateTime assignedTime = firstNonNull(asLocalDateTime(snapshot.assignedTime()),
                previous != null ? previous.assignedTime() : null);
        LocalDateTime startTime = firstNonNull(asLocalDateTime(snapshot.startTime()),
                previous != null ? previous.startTime() : null);
        LocalDateTime completeTime = firstNonNull(asLocalDateTime(snapshot.completeTime()), now);
        LocalDateTime updateTime = firstNonNull(asLocalDateTime(snapshot.updateTime()), now);
        Map<String, Object> input = previous != null ? previous.input() : eventCodeInput(snapshot.eventCode());
        String latestAttemptId = firstNonBlank(snapshot.attemptId(), previous != null ? previous.latestAttemptId() : null);
        String latestAttemptWorkerId = firstNonBlank(snapshot.workerId(),
                previous != null ? previous.latestAttemptWorkerId() : null);
        String latestAttemptBatchId = firstNonBlank(snapshot.batchId(),
                previous != null ? previous.latestAttemptBatchId() : null);
        taskDetailStore.upsertTaskMessageProjection(taskId, new TaskDetailStore.TaskMessageProjection(
                messageId,
                taskId,
                input,
                firstNonBlank(snapshot.payloadRef(), previous != null ? previous.payloadRef() : null),
                parseStatus(snapshot.status()),
                assignedTime,
                createTime,
                updateTime,
                startTime,
                completeTime,
                snapshot.retryCount(),
                resolveMaxRetryCount(snapshot, previous),
                snapshot.errorMessage(),
                snapshot.errorCode(),
                parseFinalReason(snapshot.finalReason()),
                copyMap(snapshot.output()),
                latestAttemptId,
                latestAttemptWorkerId,
                latestAttemptBatchId
        ));
        if (latestAttemptId != null) {
            taskDetailStore.upsertTaskMessageAttemptProjection(taskId, messageId,
                    new TaskDetailStore.TaskMessageAttemptProjection(
                            latestAttemptId,
                            taskId,
                            messageId,
                            snapshot.retryCount() + 1,
                            latestAttemptWorkerId,
                            latestAttemptBatchId,
                            parseAttemptStatus(snapshot.status()),
                            parseAttemptFinalReason(snapshot.finalReason()),
                            snapshot.errorMessage(),
                            snapshot.errorCode(),
                            copyMap(snapshot.output())
                    ));
        }
    }

    private TaskReviewItem toReviewItem(TaskDetailStore.TaskMessageProjection projection) {
        return new TaskReviewItem(
                projection.messageId(),
                resolveEventCode(projection.input()),
                enumName(projection.status()),
                enumName(projection.finalReason()),
                projection.payloadRef(),
                projection.retryCount(),
                projection.maxRetryCount(),
                projection.createTime(),
                projection.assignedTime(),
                projection.startTime(),
                projection.completeTime(),
                projection.updateTime(),
                projection.input(),
                projection.latestAttemptWorkerId(),
                projection.latestAttemptBatchId(),
                projection.latestAttemptId(),
                projection.errorCode(),
                projection.errorMessage(),
                projection.output()
        );
    }

    private TaskReviewAttempt toReviewAttempt(TaskDetailStore.TaskMessageAttemptProjection projection) {
        return new TaskReviewAttempt(
                projection.attemptId(),
                projection.taskId(),
                projection.messageId(),
                projection.attemptNo(),
                projection.workerId(),
                projection.batchId(),
                enumName(projection.status()),
                enumName(projection.finalReason()),
                projection.errorCode(),
                projection.errorMessage(),
                projection.output()
        );
    }

    private static String resolveEventCode(Map<String, Object> input) {
        if (input == null) {
            return null;
        }
        Object rawValue = input.get("eventCode");
        return rawValue == null ? null : String.valueOf(rawValue);
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static TaskMessageProjectionStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return TaskMessageProjectionStatus.FAILED;
        }
        return TaskMessageProjectionStatus.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT));
    }

    private static TaskMessageProjectionFinalReason parseFinalReason(String finalReason) {
        if (finalReason == null || finalReason.isBlank()) {
            return null;
        }
        return TaskMessageProjectionFinalReason.valueOf(finalReason.trim().toUpperCase(java.util.Locale.ROOT));
    }

    private static TaskMessageAttemptProjectionStatus parseAttemptStatus(String status) {
        if (status == null || status.isBlank()) {
            return TaskMessageAttemptProjectionStatus.FAILED;
        }
        return switch (status.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "SUCCESS" -> TaskMessageAttemptProjectionStatus.SUCCEEDED;
            case "EXPIRED" -> TaskMessageAttemptProjectionStatus.EXPIRED;
            default -> TaskMessageAttemptProjectionStatus.FAILED;
        };
    }

    private static TaskMessageAttemptProjectionFinalReason parseAttemptFinalReason(String finalReason) {
        if (finalReason == null || finalReason.isBlank()) {
            return null;
        }
        return switch (finalReason.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "BUSINESS_SUCCESS" -> TaskMessageAttemptProjectionFinalReason.SUCCESS;
            case "LEASE_EXPIRED" -> TaskMessageAttemptProjectionFinalReason.LEASE_EXPIRED;
            case "TIMEOUT" -> TaskMessageAttemptProjectionFinalReason.TIMEOUT;
            case "WORKER_LOST" -> TaskMessageAttemptProjectionFinalReason.WORKER_LOST;
            case "MANUAL_CANCELLED" -> TaskMessageAttemptProjectionFinalReason.MANUAL_CANCELLED;
            case "REVOKED_FOR_RETRY" -> TaskMessageAttemptProjectionFinalReason.REVOKED_FOR_RETRY;
            default -> TaskMessageAttemptProjectionFinalReason.BUSINESS_FAILURE;
        };
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private static <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }

    private static LocalDateTime asLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    private static int resolveMaxRetryCount(TaskWorkFinalSnapshot snapshot,
                                            TaskDetailStore.TaskMessageProjection previous) {
        if (snapshot.maxRetryCount() > 0 || previous == null) {
            return snapshot.maxRetryCount();
        }
        return previous.maxRetryCount();
    }

    private static Map<String, Object> eventCodeInput(String eventCode) {
        if (eventCode == null || eventCode.isBlank()) {
            return null;
        }
        return Map.of("eventCode", eventCode);
    }

    private static Map<String, Object> copyMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return Map.copyOf(new LinkedHashMap<>(values));
    }
}
