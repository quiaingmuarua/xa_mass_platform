package com.xa.mass.transport.model;

import java.util.Objects;

/**
 * Adapter-neutral result of handing one task dispatch item to a transport.
 */
public final class DispatchOutcome {

    private final String adapterId;
    private final String workerId;
    private final String taskId;
    private final String messageId;
    private final DispatchOutcomeStatus status;
    private final boolean retryable;
    private final String reason;

    public DispatchOutcome(String adapterId,
                           String workerId,
                           String taskId,
                           String messageId,
                           DispatchOutcomeStatus status,
                           boolean retryable,
                           String reason) {
        this.adapterId = normalize(adapterId);
        this.workerId = workerId;
        this.taskId = taskId;
        this.messageId = messageId;
        this.status = Objects.requireNonNull(status, "status");
        this.retryable = retryable;
        this.reason = reason;
    }

    public static DispatchOutcome sent(String adapterId, TaskDispatchItem item) {
        return fromItem(adapterId, item, DispatchOutcomeStatus.SENT, false, null);
    }

    public static DispatchOutcome queued(String adapterId, TaskDispatchItem item) {
        return fromItem(adapterId, item, DispatchOutcomeStatus.QUEUED, false, null);
    }

    public static DispatchOutcome endpointOffline(String adapterId, TaskDispatchItem item, String reason) {
        return fromItem(adapterId, item, DispatchOutcomeStatus.ENDPOINT_OFFLINE, true, reason);
    }

    public static DispatchOutcome backpressureRejected(String adapterId, TaskDispatchItem item, String reason) {
        return fromItem(adapterId, item, DispatchOutcomeStatus.BACKPRESSURE_REJECTED, true, reason);
    }

    public static DispatchOutcome invalid(String adapterId, TaskDispatchItem item, String reason) {
        return fromItem(adapterId, item, DispatchOutcomeStatus.INVALID_ITEM, false, reason);
    }

    public static DispatchOutcome adapterUnavailable(String adapterId, TaskDispatchItem item, String reason) {
        return fromItem(adapterId, item, DispatchOutcomeStatus.ADAPTER_UNAVAILABLE, true, reason);
    }

    public static DispatchOutcome failed(String adapterId, TaskDispatchItem item, String reason, boolean retryable) {
        return fromItem(adapterId, item, DispatchOutcomeStatus.FAILED, retryable, reason);
    }

    private static DispatchOutcome fromItem(String adapterId,
                                            TaskDispatchItem item,
                                            DispatchOutcomeStatus status,
                                            boolean retryable,
                                            String reason) {
        return new DispatchOutcome(
                adapterId,
                item != null ? item.getWorkerId() : null,
                item != null ? item.getTaskId() : null,
                item != null ? item.getMessageId() : null,
                status,
                retryable,
                reason
        );
    }

    public String getAdapterId() {
        return adapterId;
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getMessageId() {
        return messageId;
    }

    public DispatchOutcomeStatus getStatus() {
        return status;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public String getReason() {
        return reason;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
