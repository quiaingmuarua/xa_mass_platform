package com.xa.mass.api.review;

import com.xa.mass.api.review.TaskReviewReadModel.TaskReviewAttempt;
import com.xa.mass.api.review.TaskReviewReadModel.TaskReviewItem;
import com.xa.mass.api.review.TaskReviewReadModel.TaskReviewStats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory server review/export materialization store.
 */
public final class InMemoryTaskReviewStore implements TaskReviewStore {

    private final Map<String, Map<String, TaskReviewItem>> itemsByTask = new ConcurrentHashMap<>();
    private final Map<String, Map<String, List<TaskReviewAttempt>>> attemptsByTaskAndMessage =
            new ConcurrentHashMap<>();

    @Override
    public boolean upsertItem(String taskId, TaskReviewItem item) {
        if (isBlank(taskId) || item == null || isBlank(item.messageId())) {
            return false;
        }
        itemsByTask
                .computeIfAbsent(taskId, ignored -> new ConcurrentHashMap<>())
                .put(item.messageId(), item);
        return true;
    }

    @Override
    public Optional<TaskReviewItem> findItem(String taskId, String messageId) {
        Map<String, TaskReviewItem> items = itemsByTask.get(taskId);
        return items == null ? Optional.empty() : Optional.ofNullable(items.get(messageId));
    }

    @Override
    public List<TaskReviewItem> listItems(String taskId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Map<String, TaskReviewItem> items = itemsByTask.get(taskId);
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.values().stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(TaskReviewItem::createTime,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(limit)
                .toList();
    }

    @Override
    public boolean upsertAttempt(String taskId, String messageId, TaskReviewAttempt attempt) {
        if (isBlank(taskId) || isBlank(messageId) || attempt == null || isBlank(attempt.attemptId())) {
            return false;
        }
        attemptsByTaskAndMessage
                .computeIfAbsent(taskId, ignored -> new ConcurrentHashMap<>())
                .compute(messageId, (ignored, existing) -> upsertAttempt(existing, attempt));
        return true;
    }

    @Override
    public List<TaskReviewAttempt> listAttempts(String taskId, String messageId) {
        Map<String, List<TaskReviewAttempt>> attemptsByMessage = attemptsByTaskAndMessage.get(taskId);
        if (attemptsByMessage == null) {
            return List.of();
        }
        List<TaskReviewAttempt> attempts = attemptsByMessage.get(messageId);
        return attempts == null ? List.of() : List.copyOf(attempts);
    }

    @Override
    public TaskReviewStats stats(String taskId) {
        Map<String, TaskReviewItem> items = itemsByTask.get(taskId);
        if (items == null || items.isEmpty()) {
            return TaskReviewStats.empty();
        }
        long total = 0L;
        long success = 0L;
        long failed = 0L;
        long expired = 0L;
        long processing = 0L;
        for (TaskReviewItem item : items.values()) {
            if (item == null) {
                continue;
            }
            total++;
            String status = normalize(item.status());
            if ("SUCCESS".equals(status)) {
                success++;
            } else if ("FAILED".equals(status)) {
                failed++;
            } else if ("EXPIRED".equals(status)) {
                expired++;
            } else if ("ASSIGNED".equals(status) || "RUNNING".equals(status)) {
                processing++;
            }
        }
        return new TaskReviewStats(total, success, failed, expired, processing);
    }

    private static List<TaskReviewAttempt> upsertAttempt(List<TaskReviewAttempt> existing,
                                                         TaskReviewAttempt attempt) {
        List<TaskReviewAttempt> next = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
        for (int i = 0; i < next.size(); i++) {
            if (attempt.attemptId().equals(next.get(i).attemptId())) {
                next.set(i, attempt);
                return List.copyOf(next);
            }
        }
        next.add(attempt);
        return List.copyOf(next);
    }

    private static String normalize(String status) {
        return status == null ? "" : status.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
