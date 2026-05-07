package com.xa.mass.base.runtime.dispatch;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Runtime-native dispatch-ready binding carried across the engine -> transport
 * handoff seam.
 *
 * <p>The hot-path carrier is runtime-owned data: message identity, payload,
 * retry summary, and active attempt/lease ownership. Compatibility views can
 * still be synthesized for bounded tests or shell/debug callers, but dispatch
 * routing must not depend on persisted {@link TaskMsg}/{@link TaskMsgAttempt}
 * object graphs.</p>
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
    private final String workerContextId;
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
                               String workerContextId,
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
        this.workerContextId = workerContextId;
        this.batchId = batchId;
    }

    /**
     * Temporary compatibility constructor used by tests and bounded callers
     * that still build bindings from compatibility projections.
     */
    @Deprecated
    public TaskDispatchBinding(TaskMsg taskMsg, TaskMsgAttempt attempt) {
        this(
                taskMsg != null ? taskMsg.getTaskId() : null,
                taskMsg != null ? taskMsg.getMessageId() : null,
                null,
                taskMsg != null ? taskMsg.getInput() : null,
                taskMsg != null ? taskMsg.getPayloadRef() : null,
                taskMsg != null ? taskMsg.getRetryCount() : 0,
                attempt != null ? attempt.getAttemptId() : null,
                attempt != null ? attempt.getAttemptNo() : 1,
                null,
                attempt != null ? attempt.getWorkerId() : null,
                attempt != null ? attempt.getWorkerContextId() : null,
                attempt != null ? attempt.getBatchId() : null
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

    public String workerContextId() {
        return workerContextId;
    }

    public String batchId() {
        return batchId;
    }

    /**
     * Compatibility projection view used only by bounded tests and temporary
     * callers during the migration away from projection-driven dispatch.
     */
    @Deprecated
    @CompatibilityProjectionOnly
    public TaskMsg taskMsg() {
        TaskMsg taskMsg = new TaskMsg(messageId, taskId, payload, payloadRef);
        taskMsg.setRetryCount(retryCount);
        taskMsg.applyLatestAttemptProjection(attemptId, workerId, workerContextId, batchId);
        return taskMsg;
    }

    /**
     * Compatibility projection view used only by bounded tests and temporary
     * callers during the migration away from projection-driven dispatch.
     */
    @Deprecated
    @CompatibilityProjectionOnly
    public TaskMsgAttempt attempt() {
        TaskMsgAttempt attempt = new TaskMsgAttempt(attemptId, taskId, messageId, attemptNo);
        attempt.setWorkerId(workerId);
        attempt.setWorkerContextId(workerContextId);
        attempt.setBatchId(batchId);
        return attempt;
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
