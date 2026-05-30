package com.xa.mass.api.review;

import java.util.List;
import java.util.Objects;

/**
 * Review read model backed by the server-owned review materialization store.
 */
public final class TaskReviewStoreTaskReviewReadModel implements TaskReviewReadModel {

    private final TaskReviewStore store;

    public TaskReviewStoreTaskReviewReadModel(TaskReviewStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public TaskReviewSnapshot loadReview(String taskId, int previewLimit) {
        TaskReviewStats stats = loadStats(taskId);
        List<TaskReviewItem> preview = loadItems(taskId, previewLimit);
        return new TaskReviewSnapshot(stats, preview);
    }

    @Override
    public List<TaskReviewItem> loadItems(String taskId, int limit) {
        return store.listItems(taskId, Math.max(1, limit));
    }

    @Override
    public List<TaskReviewAttempt> loadAttempts(String taskId, String messageId) {
        return store.listAttempts(taskId, messageId);
    }

    @Override
    public TaskReviewStats loadStats(String taskId) {
        TaskReviewStats stats = store.stats(taskId);
        return stats == null ? TaskReviewStats.empty() : stats;
    }
}
