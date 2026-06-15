package com.xa.mass.transport.model;

import java.util.Objects;

/**
 * Final-hop adapter request for one assigned task dispatch.
 */
public final class AdapterDispatchRequest {

    private final String deliveryId;
    private final String adapterId;
    private final String selectedWorkerId;
    private final TaskDispatchContent content;
    private final TaskDispatchExecutionContext executionContext;
    private final long createdAtEpochMillis;

    public AdapterDispatchRequest(String deliveryId,
                                  String adapterId,
                                  String selectedWorkerId,
                                  TaskDispatchContent content,
                                  TaskDispatchExecutionContext executionContext,
                                  long createdAtEpochMillis) {
        this.deliveryId = requireText(deliveryId, "deliveryId");
        this.adapterId = requireAdapterId(adapterId);
        this.selectedWorkerId = requireText(selectedWorkerId, "selectedWorkerId");
        this.content = Objects.requireNonNull(content, "content");
        this.executionContext = Objects.requireNonNull(executionContext, "executionContext");
        this.createdAtEpochMillis = Math.max(0L, createdAtEpochMillis);
    }

    public String deliveryId() {
        return deliveryId;
    }

    public String adapterId() {
        return adapterId;
    }

    public String selectedWorkerId() {
        return selectedWorkerId;
    }

    public TaskDispatchContent content() {
        return content;
    }

    public TaskDispatchExecutionContext executionContext() {
        return executionContext;
    }

    public long createdAtEpochMillis() {
        return createdAtEpochMillis;
    }

    private static String requireAdapterId(String value) {
        String normalized = TransportDeliveryAddressing.normalizeAdapterId(value);
        if (normalized == null) {
            throw new IllegalArgumentException("adapterId must not be blank");
        }
        return normalized;
    }

    private static String requireText(String value, String fieldName) {
        String normalized = TransportDeliveryAddressing.normalizeText(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
