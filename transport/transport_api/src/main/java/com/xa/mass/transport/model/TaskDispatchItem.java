package com.xa.mass.transport.model;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.transport.packet.PacketType;
import com.xa.mass.transport.packet.TransportPacket;
import com.xa.mass.transport.payload.TransportJsonValueNormalizer;

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
    private final String routeKey;
    private final String workerId;
    private final String workerContextId;
    private final String batchId;
    private final Map<String, Object> input;
    private final Map<String, Object> sharedConfig;
    private final Map<String, Object> transportPayload;

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
                attemptId, null, workerId, workerContextId, batchId, input, sharedConfig, false, null);
    }

    public TaskDispatchItem(String taskId,
                            String messageId,
                            String eventCode,
                            String taskName,
                            String project,
                            String userId,
                            int retryCount,
                            String attemptId,
                            String routeKey,
                            String workerId,
                            String workerContextId,
                            String batchId,
                            Map<String, Object> input,
                            Map<String, Object> sharedConfig) {
        this(taskId, messageId, eventCode, taskName, project, userId, retryCount,
                attemptId, routeKey, workerId, workerContextId, batchId, input, sharedConfig, false, null);
    }

    private TaskDispatchItem(String taskId,
                             String messageId,
                             String eventCode,
                             String taskName,
                             String project,
                             String userId,
                             int retryCount,
                             String attemptId,
                             String routeKey,
                             String workerId,
                             String workerContextId,
                             String batchId,
                             Map<String, Object> input,
                             Map<String, Object> sharedConfig,
                             boolean trustedImmutablePayload,
                             Map<String, Object> transportPayload) {
        this.taskId = requireText(taskId, "taskId");
        this.messageId = requireText(messageId, "messageId");
        this.eventCode = requireText(eventCode, "eventCode");
        this.taskName = taskName;
        this.project = project;
        this.userId = userId;
        this.retryCount = retryCount;
        this.attemptId = attemptId;
        this.routeKey = routeKey;
        this.workerId = workerId;
        this.workerContextId = workerContextId;
        this.batchId = batchId;
        this.input = trustedImmutablePayload
                ? trustedMap(input)
                : normalizeObject(input, TransportPacket.PAYLOAD_INPUT);
        this.sharedConfig = trustedImmutablePayload
                ? trustedMap(sharedConfig)
                : normalizeObject(sharedConfig, TransportPacket.PAYLOAD_SHARED_CONFIG);
        this.transportPayload = transportPayload != null
                ? trustedMap(transportPayload)
                : buildTransportPayload(taskName, project, userId, retryCount, workerId, workerContextId, batchId,
                this.input, this.sharedConfig);
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
                firstNonBlank(dispatchBinding.eventCode(), task.eventCode()),
                task.taskName(),
                task.project(),
                task.userId(),
                dispatchBinding.retryCount(),
                dispatchBinding.attemptId(),
                null,
                dispatchBinding.workerId(),
                dispatchBinding.workerContextId(),
                dispatchBinding.batchId(),
                normalizeInput(dispatchBinding.payload()),
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
                                                               String routeKey,
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
                routeKey,
                workerId,
                workerContextId,
                batchId,
                input,
                sharedConfig,
                true,
                null
        );
    }

    public static TaskDispatchItem fromTransportPacket(TransportPacket packet) {
        requireDispatchPacket(packet);
        Map<String, Object> payload = packet.payload();
        return new TaskDispatchItem(
                packet.taskId(),
                packet.messageId(),
                packet.eventCode(),
                packet.payloadString(TransportPacket.PAYLOAD_TASK_NAME),
                packet.payloadString(TransportPacket.PAYLOAD_PROJECT),
                packet.payloadString(TransportPacket.PAYLOAD_USER_ID),
                packet.payloadInt(TransportPacket.PAYLOAD_RETRY_COUNT),
                packet.attemptId(),
                packet.routeKey(),
                packet.payloadString(TransportPacket.PAYLOAD_WORKER_ID),
                packet.payloadString(TransportPacket.PAYLOAD_WORKER_CONTEXT_ID),
                packet.payloadString(TransportPacket.PAYLOAD_BATCH_ID),
                packet.payloadObject(TransportPacket.PAYLOAD_INPUT),
                packet.payloadObject(TransportPacket.PAYLOAD_SHARED_CONFIG),
                true,
                payload
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

    public String routeKey() {
        return routeKey;
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
        return transportPayload;
    }

    private static Map<String, Object> buildTransportPayload(String taskName,
                                                             String project,
                                                             String userId,
                                                             int retryCount,
                                                             String workerId,
                                                             String workerContextId,
                                                             String batchId,
                                                             Map<String, Object> input,
                                                             Map<String, Object> sharedConfig) {
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, TransportPacket.PAYLOAD_TASK_NAME, taskName);
        put(payload, TransportPacket.PAYLOAD_PROJECT, project);
        put(payload, TransportPacket.PAYLOAD_USER_ID, userId);
        payload.put(TransportPacket.PAYLOAD_RETRY_COUNT, retryCount);
        put(payload, TransportPacket.PAYLOAD_WORKER_ID, workerId);
        put(payload, TransportPacket.PAYLOAD_WORKER_CONTEXT_ID, workerContextId);
        put(payload, TransportPacket.PAYLOAD_BATCH_ID, batchId);
        payload.put(TransportPacket.PAYLOAD_INPUT, input);
        payload.put(TransportPacket.PAYLOAD_SHARED_CONFIG, sharedConfig);
        return Map.copyOf(payload);
    }

    private static Map<String, Object> normalizeObject(Map<String, Object> values, String fieldName) {
        return TransportJsonValueNormalizer.normalizeObject(values, fieldName);
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeInput(Map<String, Object> rawInput) {
        if (rawInput == null || rawInput.isEmpty()) {
            return Map.of();
        }
        if (isWrappedJsonPayload(rawInput)) {
            Object data = rawInput.get("data");
            return normalizeObject((Map<String, Object>) data, TransportPacket.PAYLOAD_INPUT);
        }
        if (isWrappedTextPayload(rawInput)) {
            return Map.of("text", rawInput.get("text"));
        }
        return normalizeObject(rawInput, TransportPacket.PAYLOAD_INPUT);
    }

    private static boolean isWrappedJsonPayload(Map<String, Object> rawInput) {
        if (rawInput == null) {
            return false;
        }
        Object data = rawInput.get("data");
        if (!(data instanceof Map<?, ?>)) {
            return false;
        }
        Object type = rawInput.get("type");
        return type instanceof String text && "json".equalsIgnoreCase(text);
    }

    private static boolean isWrappedTextPayload(Map<String, Object> rawInput) {
        if (rawInput == null) {
            return false;
        }
        Object text = rawInput.get("text");
        if (!(text instanceof String)) {
            return false;
        }
        Object type = rawInput.get("type");
        return type instanceof String value && "text".equalsIgnoreCase(value);
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return null;
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

}
