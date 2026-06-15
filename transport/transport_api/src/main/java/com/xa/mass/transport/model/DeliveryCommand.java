package com.xa.mass.transport.model;

import java.util.Objects;

/**
 * Assigned-worker delivery intent accepted by the transport executor.
 *
 * <p>The command carries item-level assignment facts only. Adapter, lane,
 * route-owner, node, and session evidence live in transport-owned target
 * resolution, batches, endpoint evidence, and final-hop envelopes.</p>
 */
public final class DeliveryCommand {

    private final String commandId;
    private final String deliveryBucketId;
    private final String selectedWorkerId;
    private final TaskDispatchContent content;
    private final TaskDispatchExecutionContext executionContext;
    private final long deadlineEpochMillis;
    private final long createdAtEpochMillis;

    public DeliveryCommand(String commandId,
                           String deliveryBucketId,
                           String selectedWorkerId,
                           TaskDispatchContent content,
                           TaskDispatchExecutionContext executionContext,
                           long deadlineEpochMillis,
                           long createdAtEpochMillis) {
        this.commandId = requireText(commandId, "commandId");
        this.deliveryBucketId = requireText(deliveryBucketId, "deliveryBucketId");
        this.selectedWorkerId = requireText(selectedWorkerId, "selectedWorkerId");
        this.content = Objects.requireNonNull(content, "content");
        this.executionContext = Objects.requireNonNull(executionContext, "executionContext");
        this.deadlineEpochMillis = Math.max(0L, deadlineEpochMillis);
        this.createdAtEpochMillis = Math.max(0L, createdAtEpochMillis);
    }

    public String getCommandId() {
        return commandId;
    }

    public String getDeliveryBucketId() {
        return deliveryBucketId;
    }

    public String getSelectedWorkerId() {
        return selectedWorkerId;
    }

    public TaskDispatchContent getContent() {
        return content;
    }

    public TaskDispatchExecutionContext getExecutionContext() {
        return executionContext;
    }

    public long getDeadlineEpochMillis() {
        return deadlineEpochMillis;
    }

    public long getCreatedAtEpochMillis() {
        return createdAtEpochMillis;
    }

    private static String requireText(String value, String fieldName) {
        String normalized = TransportDeliveryAddressing.normalizeText(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
