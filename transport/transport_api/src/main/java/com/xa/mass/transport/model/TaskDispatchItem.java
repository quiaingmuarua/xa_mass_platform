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
 * Worker-facing dispatch view reconstructed from or assembled into the
 * transport packet mainline.
 *
 * <p>This class is intentionally not the transport queue/store protocol.
 * {@link TransportPacket} remains the transport-owned main protocol. This view
 * exists for worker APIs and adapter codecs that need the dispatch payload in a
 * convenient structured shape while still carrying bounded runtime metadata
 * such as {@link #attemptId()} and {@link #routeKey()}.</p>
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
                            String batchId,
                            Map<String, Object> input,
                            Map<String, Object> sharedConfig) {
        this(taskId, messageId, eventCode, taskName, project, userId, retryCount,
                null, workerId, batchId, input, sharedConfig);
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
                            String batchId,
                            Map<String, Object> input,
                            Map<String, Object> sharedConfig) {
        this(taskId, messageId, eventCode, taskName, project, userId, retryCount,
                attemptId, null, workerId, batchId, input, sharedConfig, false, null);
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
                            String batchId,
                            Map<String, Object> input,
                            Map<String, Object> sharedConfig) {
        this(taskId, messageId, eventCode, taskName, project, userId, retryCount,
                attemptId, routeKey, workerId, batchId, input, sharedConfig, false, null);
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
                             String batchId,
                             Map<String, Object> input,
                             Map<String, Object> sharedConfig,
                             boolean trustedImmutablePayload,
                             Map<String, Object> transportPayload) {
        this.taskId = requireText(taskId, "taskId");
        this.messageId = requireText(messageId, "messageId");
        this.eventCode = optionalText(eventCode);
        this.taskName = taskName;
        this.project = project;
        this.userId = userId;
        this.retryCount = retryCount;
        this.attemptId = attemptId;
        this.routeKey = routeKey;
        this.workerId = workerId;
        this.batchId = batchId;
        this.input = trustedImmutablePayload
                ? trustedMap(input)
                : normalizeObject(input, TransportPacket.PAYLOAD_INPUT);
        this.sharedConfig = trustedImmutablePayload
                ? trustedMap(sharedConfig)
                : normalizeObject(sharedConfig, TransportPacket.PAYLOAD_SHARED_CONFIG);
        this.transportPayload = transportPayload != null
                ? trustedMap(transportPayload)
                : buildTransportPayload(taskName, project, userId, retryCount, workerId, batchId,
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
        return fromAssignedDelivery(
                null,
                dispatchBinding.workerId(),
                TaskDispatchContent.from(task, dispatchBinding),
                TaskDispatchExecutionContext.from(task, dispatchBinding)
        );
    }

    public static TaskDispatchItem fromAssignedDelivery(String routeKey,
                                                        String selectedWorkerId,
                                                        TaskDispatchContent content,
                                                        TaskDispatchExecutionContext executionContext) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(executionContext, "executionContext");
        return new TaskDispatchItem(
                content.taskId(),
                content.messageId(),
                content.eventCode(),
                executionContext.taskName(),
                executionContext.project(),
                executionContext.userId(),
                executionContext.retryCount(),
                executionContext.attemptId(),
                routeKey,
                selectedWorkerId,
                executionContext.batchId(),
                content.input(),
                content.sharedConfig(),
                true,
                null
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

    public String getBatchId() {
        return batchId;
    }

    public Map<String, Object> getInput() {
        return input;
    }

    public Map<String, Object> getSharedConfig() {
        return sharedConfig;
    }

    public Map<String, Object> transportPayloadView() {
        return transportPayload;
    }

    private static Map<String, Object> buildTransportPayload(String taskName,
                                                             String project,
                                                             String userId,
                                                             int retryCount,
                                                             String workerId,
                                                             String batchId,
                                                             Map<String, Object> input,
                                                             Map<String, Object> sharedConfig) {
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, TransportPacket.PAYLOAD_TASK_NAME, taskName);
        put(payload, TransportPacket.PAYLOAD_PROJECT, project);
        put(payload, TransportPacket.PAYLOAD_USER_ID, userId);
        payload.put(TransportPacket.PAYLOAD_RETRY_COUNT, retryCount);
        put(payload, TransportPacket.PAYLOAD_WORKER_ID, workerId);
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

    private static Map<String, Object> normalizeInput(Map<String, Object> rawInput) {
        return TaskDispatchContent.normalizeInput(rawInput);
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

    private static String optionalText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

}
