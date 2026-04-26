package com.xa.mass.transport.model;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskSharedConfig;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Transport-neutral logical dispatch item delivered to worker transports.
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

    public static TaskDispatchItem from(Task task, TaskMsg taskMsg) {
        return new TaskDispatchItem(
                task.getTid(),
                taskMsg.getMessageId(),
                TaskSharedConfig.sdkEventCode(task),
                task.getTaskName(),
                task.getProject(),
                task.getUser() != null ? task.getUser().getUserId() : null,
                taskMsg.getRetryCount(),
                taskMsg.latestAttemptId(),
                taskMsg.getLatestAttemptWorkerId(),
                taskMsg.getLatestAttemptWorkerContextId(),
                taskMsg.getLatestAttemptBatchId(),
                normalizeInput(task, taskMsg),
                task.getSharedConfig()
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
    private static Map<String, Object> normalizeInput(Task task, TaskMsg taskMsg) {
        Map<String, Object> rawInput = taskMsg == null ? null : taskMsg.getInput();
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

    private static String sdkPayloadType(Task task) {
        if (task == null || task.getSharedConfig() == null) {
            return null;
        }
        Object sdk = task.getSharedConfig().get("_sdk");
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
