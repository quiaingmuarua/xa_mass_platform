package com.xa.mass.transport.model;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Transport-neutral logical dispatch item delivered to worker transports.
 *
 * <p>This class is currently a narrow hybrid: worker-facing task payload plus
 * runtime dispatch metadata used by adapters. Internal metadata such as
 * {@link #attemptId()} intentionally avoids JavaBean getter naming so worker
 * API serializers do not expose it by convention.</p>
 *
 * <p>Do not add more lifecycle or security state here. If this hybrid becomes
 * a constraint, split worker payload and runtime dispatch context deliberately
 * across adapter codec and worker API tests.</p>
 */
public final class TaskDispatchItem {

    private final String taskId;
    private final String messageId;
    private final String eventCode;
    private final String taskName;
    private final String project;
    private final String userId;
    private final int retryCount;
    private final String attemptId;
    private final String workerId;
    private final String workerContextId;
    private final String batchId;
    private final Map<String, Object> input;
    private final Map<String, Object> sharedConfig;

    public TaskDispatchItem(String taskId,
                            String messageId,
                            String eventCode,
                            String taskName,
                            String project,
                            String userId,
                            int retryCount,
                            String workerId,
                            String workerContextId,
                            String batchId,
                            Map<String, Object> input,
                            Map<String, Object> sharedConfig) {
        this(taskId, messageId, eventCode, taskName, project, userId, retryCount,
                null, workerId, workerContextId, batchId, input, sharedConfig);
    }

    public TaskDispatchItem(String taskId,
                            String messageId,
                            String eventCode,
                            String taskName,
                            String project,
                            String userId,
                            int retryCount,
                            String attemptId,
                            String workerId,
                            String workerContextId,
                            String batchId,
                            Map<String, Object> input,
                            Map<String, Object> sharedConfig) {
        this.taskId = taskId;
        this.messageId = messageId;
        this.eventCode = eventCode;
        this.taskName = taskName;
        this.project = project;
        this.userId = userId;
        this.retryCount = retryCount;
        this.attemptId = attemptId;
        this.workerId = workerId;
        this.workerContextId = workerContextId;
        this.batchId = batchId;
        this.input = immutableCopy(input);
        this.sharedConfig = immutableCopy(sharedConfig);
    }

    public String getTaskId() {
        return taskId;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getEventCode() {
        return eventCode;
    }

    public static TaskDispatchItem from(TaskDispatchContext task, TaskDispatchBinding dispatchBinding) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(dispatchBinding, "dispatchBinding");
        return new TaskDispatchItem(
                task.taskId(),
                dispatchBinding.messageId(),
                task.eventCode(),
                task.taskName(),
                task.project(),
                task.userId(),
                dispatchBinding.retryCount(),
                dispatchBinding.attemptId(),
                dispatchBinding.workerId(),
                dispatchBinding.workerContextId(),
                dispatchBinding.batchId(),
                normalizeInput(task, dispatchBinding.payload()),
                task.sharedConfig()
        );
    }

    public String getTaskName() {
        return taskName;
    }

    public String getProject() {
        return project;
    }

    public String getUserId() {
        return userId;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public String attemptId() {
        return attemptId;
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getWorkerContextId() {
        return workerContextId;
    }

    public String getBatchId() {
        return batchId;
    }

    public Map<String, Object> getInput() {
        return input;
    }

    public Map<String, Object> getSharedConfig() {
        return sharedConfig;
    }

    public TaskDispatchRuntimeMetadata runtimeMetadata() {
        return new TaskDispatchRuntimeMetadata(
                attemptId,
                workerId,
                workerContextId,
                batchId
        );
    }

    public TaskDispatchWireView wireView() {
        return new TaskDispatchWireView(
                taskId,
                messageId,
                eventCode,
                taskName,
                project,
                userId,
                retryCount,
                workerId,
                workerContextId,
                batchId,
                input,
                sharedConfig
        );
    }

    public Map<String, Object> mergedPayload() {
        Map<String, Object> payload = new LinkedHashMap<>(input);
        payload.putAll(sharedConfig);
        payload.put("taskId", taskId);
        payload.put("taskName", taskName);
        payload.put("eventCode", eventCode);
        payload.put("project", project);
        payload.put("userId", userId);
        payload.put("workerId", workerId);
        payload.put("workerContextId", workerContextId);
        payload.put("batchId", batchId);
        payload.put("retryCount", retryCount);
        return Collections.unmodifiableMap(payload);
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeInput(TaskDispatchContext task, Map<String, Object> rawInput) {
        if (rawInput == null || rawInput.isEmpty()) {
            return Collections.emptyMap();
        }
        String payloadType = sdkPayloadType(task);
        if ("JSON".equals(payloadType)) {
            Object data = rawInput.get("data");
            if (data instanceof Map<?, ?> map) {
                return immutableCopy((Map<String, Object>) map);
            }
        }
        if ("TEXT".equals(payloadType)) {
            Object text = rawInput.get("text");
            if (text instanceof String value) {
                return Map.of("text", value);
            }
        }
        return immutableCopy(rawInput);
    }

    private static String sdkPayloadType(TaskDispatchContext task) {
        if (task == null || task.sharedConfig() == null) {
            return null;
        }
        Object sdk = task.sharedConfig().get("_sdk");
        if (!(sdk instanceof Map<?, ?> metadata)) {
            return null;
        }
        Object payloadType = metadata.get("payloadType");
        if (!(payloadType instanceof String value) || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase();
    }
}
