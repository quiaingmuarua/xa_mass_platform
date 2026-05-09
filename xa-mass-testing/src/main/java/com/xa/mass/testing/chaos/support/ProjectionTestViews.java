package com.xa.mass.testing.chaos.support;

import com.xa.mass.storage.api.TaskDetailStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ProjectionTestViews {

    private ProjectionTestViews() {
    }

    public static CompatibilityMessageSnapshot snapshot(TaskDetailStore taskDetailStore, String taskId, int limit) {
        TaskDetailStore store = taskDetailStore(taskDetailStore);
        List<CompatibilityMessageView> messages = new ArrayList<>();
        List<TaskDetailStore.TaskMessageProjection> projections = store.getTaskMessageProjections(taskId, limit);
        for (TaskDetailStore.TaskMessageProjection projection : projections) {
            messages.add(new CompatibilityMessageView(
                    projection.messageId(),
                    projection.taskId(),
                    projection.status() != null ? projection.status().name() : null,
                    projection.latestAttemptId(),
                    projection.latestAttemptWorkerId(),
                    projection.latestAttemptWorkerContextId(),
                    projection.latestAttemptBatchId(),
                    projection.retryCount(),
                    projection.maxRetryCount(),
                    projection.errorMessage(),
                    projection.errorCode(),
                    projection.finalReason() != null ? projection.finalReason().name() : null,
                    projection.payloadRef(),
                    projection.input(),
                    projection.output(),
                    projection.assignedTime(),
                    projection.createTime(),
                    projection.updateTime(),
                    projection.startTime(),
                    projection.completeTime()
            ));
        }
        long total = store.getTaskMessageStats(taskId).getTotal();
        boolean truncated = limit > 0 && total > projections.size();
        return new CompatibilityMessageSnapshot(messages, Math.max(limit, 0), truncated);
    }

    public static CompatibilityMessageView message(TaskDetailStore taskDetailStore, String taskId, String messageId) {
        TaskDetailStore.TaskMessageProjection projection =
                taskDetailStore(taskDetailStore).getTaskMessageProjection(taskId, messageId).orElse(null);
        if (projection == null) {
            return null;
        }
        return new CompatibilityMessageView(
                projection.messageId(),
                projection.taskId(),
                projection.status() != null ? projection.status().name() : null,
                projection.latestAttemptId(),
                projection.latestAttemptWorkerId(),
                projection.latestAttemptWorkerContextId(),
                projection.latestAttemptBatchId(),
                projection.retryCount(),
                projection.maxRetryCount(),
                projection.errorMessage(),
                projection.errorCode(),
                projection.finalReason() != null ? projection.finalReason().name() : null,
                projection.payloadRef(),
                projection.input(),
                projection.output(),
                projection.assignedTime(),
                projection.createTime(),
                projection.updateTime(),
                projection.startTime(),
                projection.completeTime()
        );
    }

    public static List<CompatibilityAttemptView> attempts(TaskDetailStore taskDetailStore,
                                                          String taskId,
                                                          String messageId) {
        List<CompatibilityAttemptView> attempts = new ArrayList<>();
        for (TaskDetailStore.TaskMessageAttemptProjection projection
                : taskDetailStore(taskDetailStore).getTaskMessageAttemptProjections(taskId, messageId)) {
            attempts.add(new CompatibilityAttemptView(
                    projection.attemptId(),
                    projection.taskId(),
                    projection.messageId(),
                    projection.attemptNo(),
                    projection.workerId(),
                    projection.workerContextId(),
                    projection.batchId(),
                    projection.status() != null ? projection.status().name() : null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    projection.finalReason() != null ? projection.finalReason().name() : null,
                    projection.errorMessage(),
                    projection.errorCode(),
                    projection.output(),
                    null,
                    null
            ));
        }
        return List.copyOf(attempts);
    }

    public static CompatibilityAttemptView latestActiveAttempt(TaskDetailStore taskDetailStore,
                                                               String taskId,
                                                               String messageId) {
        TaskDetailStore.TaskMessageAttemptProjection projection =
                taskDetailStore(taskDetailStore).getLatestActiveTaskMessageAttemptProjection(taskId, messageId).orElse(null);
        if (projection == null) {
            return null;
        }
        return new CompatibilityAttemptView(
                projection.attemptId(),
                projection.taskId(),
                projection.messageId(),
                projection.attemptNo(),
                projection.workerId(),
                projection.workerContextId(),
                projection.batchId(),
                projection.status() != null ? projection.status().name() : null,
                null,
                null,
                null,
                null,
                null,
                projection.finalReason() != null ? projection.finalReason().name() : null,
                projection.errorMessage(),
                projection.errorCode(),
                projection.output(),
                null,
                null
        );
    }

    private static TaskDetailStore taskDetailStore(TaskDetailStore taskDetailStore) {
        return Objects.requireNonNull(taskDetailStore, "taskDetailStore");
    }
}
