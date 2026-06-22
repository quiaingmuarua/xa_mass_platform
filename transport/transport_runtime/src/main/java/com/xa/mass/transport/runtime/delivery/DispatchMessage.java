package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.TransportDeliveryAddressing;

/**
 * Flat selected-worker dispatch item carried inside an adapter-mailbox batch.
 */
public record DispatchMessage(String deliveryId,
                              String selectedWorkerId,
                              String payload,
                              String correlationRef,
                              long deadlineEpochMillis,
                              long createdAtEpochMillis) {

    public DispatchMessage {
        deliveryId = requireText(deliveryId, "deliveryId");
        selectedWorkerId = requireText(selectedWorkerId, "selectedWorkerId");
        payload = requirePayload(payload, "payload");
        correlationRef = requireText(correlationRef, "correlationRef");
        deadlineEpochMillis = Math.max(0L, deadlineEpochMillis);
        createdAtEpochMillis = Math.max(0L, createdAtEpochMillis);
    }

    private static String requireText(String value, String fieldName) {
        String normalized = TransportDeliveryAddressing.normalizeText(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String requirePayload(String value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
        return value;
    }
}
