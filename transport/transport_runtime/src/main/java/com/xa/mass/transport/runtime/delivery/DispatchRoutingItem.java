package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.TransportDeliveryAddressing;

/**
 * Flat selected-worker dispatch item carried inside an adapter-mailbox batch.
 */
public record DispatchRoutingItem(String deliveryId,
                                  String selectedWorkerId,
                                  String payload,
                                  String correlationRef,
                                  long deadlineEpochMillis,
                                  long createdAtEpochMillis) {

    public DispatchRoutingItem {
        deliveryId = requireText(deliveryId, "deliveryId");
        selectedWorkerId = requireText(selectedWorkerId, "selectedWorkerId");
        payload = requireText(payload, "payload");
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
}
