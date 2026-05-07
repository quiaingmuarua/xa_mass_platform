package com.xa.mass.transport.model;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.transport.packet.PacketType;
import com.xa.mass.transport.packet.TransportPacket;

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

    private static final String TASK_NAME = "taskName";
    private static final String PROJECT = "project";
    private static final String USER_ID = "userId";
    private static final String RETRY_COUNT = "retryCount";
    private static final String WORKER_ID = "workerId";
    private static final String WORKER_CONTEXT_ID = "workerContextId";
    private static final String BATCH_ID = "batchId";
    private static final String INPUT = "input";
    private static final String SHARED_CONFIG = "sharedConfig";

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
        this(taskId, messageId, eventCode, taskName, project, userId, retryCount,
                attemptId, workerId, workerContextId, batchId, input, sharedConfig, false);
    }

    private TaskDispatchItem(String taskId,
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
                             Map<String, Object> sharedConfig,
                             boolean trustedImmutablePayload) {
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
        this.input = trustedImmutablePayload ? trustedMap(input) : immutableCopy(input);
        this.sharedConfig = trustedImmutablePayload ? trustedMap(sharedConfig) : immutableCopy(sharedConfig);
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

    public static TaskDispatchItem fromDecodedTransportPayload(String taskId,
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
        return new TaskDispatchItem(
                taskId,
                messageId,
                eventCode,
                taskName,
                project,
                userId,
                retryCount,
                attemptId,
                workerId,
                workerContextId,
                batchId,
                input,
                sharedConfig,
                true
        );
    }

    public static TaskDispatchItem fromTransportPacket(TransportPacket packet) {
        requireDispatchPacket(packet);
        Map<String, Object> payload = packet.payload();
        return fromDecodedTransportPayload(
                packet.taskId(),
                packet.messageId(),
                packet.eventCode(),
                stringValue(payload.get(TASK_NAME)),
                stringValue(payload.get(PROJECT)),
                stringValue(payload.get(USER_ID)),
                intValue(payload.get(RETRY_COUNT)),
                packet.attemptId(),
                stringValue(payload.get(WORKER_ID)),
                stringValue(payload.get(WORKER_CONTEXT_ID)),
                stringValue(payload.get(BATCH_ID)),
                mapValue(payload.get(INPUT)),
                mapValue(payload.get(SHARED_CONFIG))
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

    public Map<String, Object> toTransportPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, TASK_NAME, taskName);
        put(payload, PROJECT, project);
        put(payload, USER_ID, userId);
        payload.put(RETRY_COUNT, retryCount);
        put(payload, WORKER_ID, workerId);
        put(payload, WORKER_CONTEXT_ID, workerContextId);
        put(payload, BATCH_ID, batchId);
        payload.put(INPUT, input);
        payload.put(SHARED_CONFIG, sharedConfig);
        return payload;
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(new LinkedHashMap<>(values));
    }

    private static Map<String, Object> trustedMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return values;
    }

    private static void requireDispatchPacket(TransportPacket packet) {
        if (packet == null) {
            throw new IllegalArgumentException("packet must not be null");
        }
        if (packet.type() != PacketType.TASK_DISPATCH) {
            throw new IllegalArgumentException("packet must be TASK_DISPATCH");
        }
    }

    private static void put(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private static String stringValue(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        return text.trim();
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeInput(TaskDispatchContext task, Map<String, Object> rawInput) {
        if (rawInput == null || rawInput.isEmpty()) {
            return Map.of();
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
