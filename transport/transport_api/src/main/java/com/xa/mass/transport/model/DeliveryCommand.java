package com.xa.mass.transport.model;

/**
 * Assigned-worker delivery intent accepted by the transport executor.
 *
 * <p>The command carries item-level assignment facts only. Adapter, lane,
 * route-owner, node, and session evidence live in transport-owned target
 * resolution, batches, endpoint evidence, and final-hop envelopes. The deadline
 * field is delivery observation metadata; retry, reassign, compensation, and
 * final recovery remain engine-owned.</p>
 */
public final class DeliveryCommand {

    private final String commandId;
    private final String deliveryBucketId;
    private final String selectedWorkerId;
    private final String payload;
    private final String correlationRef;
    private final long deadlineEpochMillis;
    private final long createdAtEpochMillis;

    public DeliveryCommand(String commandId,
                           String deliveryBucketId,
                           String selectedWorkerId,
                           String payload,
                           String correlationRef,
                           long deadlineEpochMillis,
                           long createdAtEpochMillis) {
        this.commandId = requireText(commandId, "commandId");
        this.deliveryBucketId = requireText(deliveryBucketId, "deliveryBucketId");
        this.selectedWorkerId = requireText(selectedWorkerId, "selectedWorkerId");
        this.payload = requireText(payload, "payload");
        this.correlationRef = requireText(correlationRef, "correlationRef");
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

    public String getPayload() {
        return payload;
    }

    public String getCorrelationRef() {
        return correlationRef;
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
