package com.xa.mass.transport.starter;

import java.util.Objects;

/**
 * Stable embedded assigned-delivery message consumed by adapter-starter.
 */
public record AssignedDeliveryMessage(
        String deliveryId,
        String selectedWorkerId,
        String payload,
        String correlationRef,
        long deadlineEpochMillis,
        long createdAtEpochMillis
) {

    public AssignedDeliveryMessage {
        deliveryId = requireText(deliveryId, "deliveryId");
        selectedWorkerId = requireText(selectedWorkerId, "selectedWorkerId");
        payload = Objects.requireNonNull(payload, "payload");
        correlationRef = Objects.requireNonNull(correlationRef, "correlationRef");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
