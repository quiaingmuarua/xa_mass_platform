package com.xa.mass.base.runtime.dispatch;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Runtime-native dispatch-ready binding carried across the engine -> transport
 * handoff seam.
 *
 * <p>The hot-path carrier is runtime-owned data: message identity, payload,
 * retry summary, and active attempt/lease ownership. Dispatch routing must not
 * depend on persisted compatibility projection object graphs.</p>
 */
public final class TaskDispatchBinding {

    private final String taskId;
    private final String messageId;
    private final String eventCode;
    private final Map<String, Object> payload;
    private final String payloadRef;
    private final int retryCount;
    private final String attemptId;
    private final int attemptNo;
    private final String leaseToken;
    private final String workerId;
    private final String batchId;

    public TaskDispatchBinding(String taskId,
                               String messageId,
                               String eventCode,
                               Map<String, Object> payload,
                               String payloadRef,
                               int retryCount,
                               String attemptId,
                               int attemptNo,
                               String leaseToken,
                               String workerId,
                               String batchId) {
        this.taskId = requireText(taskId, "taskId");
        this.messageId = requireText(messageId, "messageId");
        this.eventCode = eventCode;
        this.payload = copyPayload(payload);
        this.payloadRef = payloadRef;
        this.retryCount = Math.max(0, retryCount);
        this.attemptId = attemptId;
        this.attemptNo = Math.max(1, attemptNo);
        this.leaseToken = leaseToken;
        this.workerId = workerId;
        this.batchId = batchId;
    }

    public static TaskDispatchBinding workerLevel(String taskId,
                                                  String messageId,
                                                  String eventCode,
                                                  Map<String, Object> payload,
                                                  String payloadRef,
                                                  int retryCount,
                                                  String attemptId,
                                                  int attemptNo,
                                                  String leaseToken,
                                                  String workerId,
                                                  String batchId) {
        return new TaskDispatchBinding(
                taskId,
                messageId,
                eventCode,
                payload,
                payloadRef,
                retryCount,
                attemptId,
                attemptNo,
                leaseToken,
                workerId,
                batchId
        );
    }

    public String taskId() {
        return taskId;
    }

    public String messageId() {
        return messageId;
    }

    public String eventCode() {
        return eventCode;
    }

    public Map<String, Object> payload() {
        return payload;
    }

    public String payloadRef() {
        return payloadRef;
    }

    public int retryCount() {
        return retryCount;
    }

    public String attemptId() {
        return attemptId;
    }

    public int attemptNo() {
        return attemptNo;
    }

    public String leaseToken() {
        return leaseToken;
    }

    public String workerId() {
        return workerId;
    }

    public String batchId() {
        return batchId;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static Map<String, Object> copyPayload(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(new LinkedHashMap<>(values));
    }
}
