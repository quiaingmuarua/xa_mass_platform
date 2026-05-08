package com.xa.mass.testing.chaos.support;

import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.starter.MassApplication;
import com.xa.mass.starter.MassEngine;
import com.xa.mass.starter.config.EngineConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ProjectionTestViews {

    private ProjectionTestViews() {
    }

    public static CompatibilityMessageSnapshot snapshot(MassSdkApplication app, String taskId, int limit) {
        List<CompatibilityMessageView> messages = new ArrayList<>();
        List<TaskDetailStore.TaskMessageProjection> projections = taskDetailStore(app).getTaskMessageProjections(taskId, limit);
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
        long total = taskDetailStore(app).getTaskMessageStats(taskId).getTotal();
        boolean truncated = limit > 0 && total > projections.size();
        return new CompatibilityMessageSnapshot(messages, Math.max(limit, 0), truncated);
    }

    public static CompatibilityMessageView message(MassSdkApplication app, String taskId, String messageId) {
        TaskDetailStore.TaskMessageProjection projection =
                taskDetailStore(app).getTaskMessageProjection(taskId, messageId).orElse(null);
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

    public static List<CompatibilityAttemptView> attempts(MassSdkApplication app, String taskId, String messageId) {
        List<CompatibilityAttemptView> attempts = new ArrayList<>();
        for (TaskDetailStore.TaskMessageAttemptProjection projection
                : taskDetailStore(app).getTaskMessageAttemptProjections(taskId, messageId)) {
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

    public static CompatibilityAttemptView latestActiveAttempt(MassSdkApplication app, String taskId, String messageId) {
        TaskDetailStore.TaskMessageAttemptProjection projection =
                taskDetailStore(app).getLatestActiveTaskMessageAttemptProjection(taskId, messageId).orElse(null);
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

    private static TaskDetailStore taskDetailStore(MassSdkApplication app) {
        Objects.requireNonNull(app, "app");
        MassApplication delegate = readField(app, "delegate", MassApplication.class);
        MassEngine engine = Objects.requireNonNull(delegate.getEngine(), "engine");
        EngineConfig config = Objects.requireNonNull(engine.getConfig(), "engineConfig");
        return Objects.requireNonNull(config.getTaskDetailStore(), "taskDetailStore");
    }

    private static <T> T readField(Object target, String fieldName, Class<T> type) {
        Objects.requireNonNull(target, "target");
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                java.lang.reflect.Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(target);
                if (value == null) {
                    return null;
                }
                return type.cast(value);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Failed to read field " + fieldName + " from " + target.getClass(), e);
            }
        }
        throw new IllegalStateException("Field not found: " + fieldName + " on " + target.getClass());
    }
}
