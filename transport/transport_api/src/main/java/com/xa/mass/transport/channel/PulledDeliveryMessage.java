package com.xa.mass.transport.channel;

import com.xa.mass.transport.model.TransportDeliveryAddressing;

import java.util.Objects;

/**
 * Opaque delivery message claimed by a pull-capable transport consumer.
 */
public final class PulledDeliveryMessage {

    private final String deliveryId;
    private final String selectedWorkerId;
    private final String payload;
    private final String correlationRef;
    private final long createdAtEpochMillis;

    public PulledDeliveryMessage(String deliveryId,
                                 String selectedWorkerId,
                                 String payload,
                                 String correlationRef,
                                 long createdAtEpochMillis) {
        this.deliveryId = requireText(deliveryId, "deliveryId");
        this.selectedWorkerId = requireText(selectedWorkerId, "selectedWorkerId");
        this.payload = requireText(payload, "payload");
        this.correlationRef = requireText(correlationRef, "correlationRef");
        this.createdAtEpochMillis = Math.max(0L, createdAtEpochMillis);
    }

    public String getDeliveryId() {
        return deliveryId;
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

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PulledDeliveryMessage that)) {
            return false;
        }
        return createdAtEpochMillis == that.createdAtEpochMillis
                && Objects.equals(deliveryId, that.deliveryId)
                && Objects.equals(selectedWorkerId, that.selectedWorkerId)
                && Objects.equals(payload, that.payload)
                && Objects.equals(correlationRef, that.correlationRef);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deliveryId, selectedWorkerId, payload, correlationRef, createdAtEpochMillis);
    }
}
