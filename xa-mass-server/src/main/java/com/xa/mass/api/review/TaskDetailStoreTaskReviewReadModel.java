package com.xa.mass.api.review;

import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.sdk.model.TaskItemBatchAppendReceipt;
import com.xa.mass.sdk.model.TaskWorkFinalNotification;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Transitional server read-model implementation backed by the existing
 * bounded task-detail projection store.
 */
public class TaskDetailStoreTaskReviewReadModel implements TaskReviewReadModel, TaskReviewReadModelWriter {

    private final TaskDetailStore taskDetailStore;
    private final TaskReviewMaterializer materializer;

    public TaskDetailStoreTaskReviewReadModel(TaskDetailStore taskDetailStore) {
        this.taskDetailStore = Objects.requireNonNull(taskDetailStore, "taskDetailStore");
        this.materializer = new TaskDetailStoreReviewMaterializer(taskDetailStore);
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
        materializer.apply(TaskReviewItemsAcceptedEvent.from(taskId, acceptedItems, receipt, maxRetryCount));
    }

    @Override
    public void recordWorkFinal(TaskWorkFinalNotification notification) {
        materializer.apply(TaskReviewWorkTerminalEvent.from(notification));
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

}
