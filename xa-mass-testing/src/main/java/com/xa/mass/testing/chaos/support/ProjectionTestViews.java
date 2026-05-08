package com.xa.mass.testing.chaos.support;

import com.xa.mass.engine.TaskCompatibilityQueryService;
import com.xa.mass.engine.TaskCompatibilitySnapshotPage;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.starter.MassApplication;
import com.xa.mass.starter.MassEngine;
import com.xa.mass.starter.config.EngineConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class ProjectionTestViews {

    private ProjectionTestViews() {
    }

    public static CompatibilityMessageSnapshot snapshot(MassSdkApplication app, String taskId, int limit) {
        List<CompatibilityMessageView> messages = new ArrayList<>();
        TaskCompatibilitySnapshotPage page = queryService(app).visitTaskMessageSnapshot(taskId, limit,
                (messageId, resolvedTaskId, status, latestAttemptId, latestAttemptWorkerId, latestAttemptWorkerContextId,
                 latestAttemptBatchId, retryCount, maxRetryCount, errorMessage, errorCode, finalReason, payloadRef,
                 input, output, assignedTime, createTime, updateTime, startTime, completeTime) -> messages.add(
                        new CompatibilityMessageView(
                                messageId,
                                resolvedTaskId,
                                status,
                                latestAttemptId,
                                latestAttemptWorkerId,
                                latestAttemptWorkerContextId,
                                latestAttemptBatchId,
                                retryCount,
                                maxRetryCount,
                                errorMessage,
                                errorCode,
                                finalReason,
                                payloadRef,
                                input,
                                output,
                                assignedTime,
                                createTime,
                                updateTime,
                                startTime,
                                completeTime
                        )));
        return new CompatibilityMessageSnapshot(messages, page.limit(), page.truncated());
    }

    public static CompatibilityMessageView message(MassSdkApplication app, String taskId, String messageId) {
        AtomicReference<CompatibilityMessageView> ref = new AtomicReference<>();
        boolean found = queryService(app).visitTaskMessage(taskId, messageId,
                (resolvedMessageId, resolvedTaskId, status, latestAttemptId, latestAttemptWorkerId,
                 latestAttemptWorkerContextId, latestAttemptBatchId, retryCount, maxRetryCount, errorMessage,
                 errorCode, finalReason, payloadRef, input, output, assignedTime, createTime, updateTime,
                 startTime, completeTime) -> ref.set(new CompatibilityMessageView(
                        resolvedMessageId,
                        resolvedTaskId,
                        status,
                        latestAttemptId,
                        latestAttemptWorkerId,
                        latestAttemptWorkerContextId,
                        latestAttemptBatchId,
                        retryCount,
                        maxRetryCount,
                        errorMessage,
                        errorCode,
                        finalReason,
                        payloadRef,
                        input,
                        output,
                        assignedTime,
                        createTime,
                        updateTime,
                        startTime,
                        completeTime
                )));
        return found ? ref.get() : null;
    }

    public static List<CompatibilityAttemptView> attempts(MassSdkApplication app, String taskId, String messageId) {
        List<CompatibilityAttemptView> attempts = new ArrayList<>();
        queryService(app).visitTaskMessageAttemptViews(taskId, messageId,
                (attemptId, resolvedTaskId, resolvedMessageId, attemptNo, workerId, workerContextId, batchId,
                 status, leaseExpireTime, dispatchTime, ackTime, startTime, finishTime, finalReason,
                 errorMessage, errorCode, output, createTime, updateTime) -> attempts.add(
                        new CompatibilityAttemptView(
                                attemptId,
                                resolvedTaskId,
                                resolvedMessageId,
                                attemptNo,
                                workerId,
                                workerContextId,
                                batchId,
                                status,
                                leaseExpireTime,
                                dispatchTime,
                                ackTime,
                                startTime,
                                finishTime,
                                finalReason,
                                errorMessage,
                                errorCode,
                                output,
                                createTime,
                                updateTime
                        )));
        return List.copyOf(attempts);
    }

    public static CompatibilityAttemptView latestActiveAttempt(MassSdkApplication app, String taskId, String messageId) {
        AtomicReference<CompatibilityAttemptView> ref = new AtomicReference<>();
        boolean found = queryService(app).visitLatestActiveTaskMessageAttempt(taskId, messageId,
                (attemptId, resolvedTaskId, resolvedMessageId, attemptNo, workerId, workerContextId, batchId,
                 status, leaseExpireTime, dispatchTime, ackTime, startTime, finishTime, finalReason,
                 errorMessage, errorCode, output, createTime, updateTime) -> ref.set(new CompatibilityAttemptView(
                        attemptId,
                        resolvedTaskId,
                        resolvedMessageId,
                        attemptNo,
                        workerId,
                        workerContextId,
                        batchId,
                        status,
                        leaseExpireTime,
                        dispatchTime,
                        ackTime,
                        startTime,
                        finishTime,
                        finalReason,
                        errorMessage,
                        errorCode,
                        output,
                        createTime,
                        updateTime
                )));
        return found ? ref.get() : null;
    }

    private static TaskCompatibilityQueryService queryService(MassSdkApplication app) {
        Objects.requireNonNull(app, "app");
        MassApplication delegate = readField(app, "delegate", MassApplication.class);
        MassEngine engine = Objects.requireNonNull(delegate.getEngine(), "engine");
        EngineConfig config = Objects.requireNonNull(engine.getConfig(), "engineConfig");
        config.getTaskQueryService();
        TaskManager taskManager = readField(config, "taskManager", TaskManager.class);
        if (taskManager == null) {
            throw new IllegalStateException("taskManager is unavailable for projection test views");
        }
        return new TaskCompatibilityQueryService(taskManager);
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
