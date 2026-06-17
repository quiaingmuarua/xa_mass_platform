package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.channel.PulledDeliveryMessage;
import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.TransportDeliveryAddressing;

/**
 * Queue value for polling worker delivery.
 */
public final class QueuedPulledDispatch {

    private final String deliveryId;
    private final String selectedWorkerId;
    private final String payload;
    private final String correlationRef;
    private final long createdAtEpochMillis;

    public QueuedPulledDispatch(String deliveryId,
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

    public static QueuedPulledDispatch from(DeliveryCommand command) {
        return new QueuedPulledDispatch(
                command.getCommandId(),
                command.getSelectedWorkerId(),
                command.getPayload(),
                command.getCorrelationRef(),
                command.getCreatedAtEpochMillis()
        );
    }

    public PulledDeliveryMessage toPulledDeliveryMessage() {
        return new PulledDeliveryMessage(
                deliveryId,
                selectedWorkerId,
                payload,
                correlationRef,
                createdAtEpochMillis
        );
    }

    public String deliveryId() {
        return deliveryId;
    }

    public String selectedWorkerId() {
        return selectedWorkerId;
    }

    public String payload() {
        return payload;
    }

    public String correlationRef() {
        return correlationRef;
    }

    public long createdAtEpochMillis() {
        return createdAtEpochMillis;
    }

    private static String requireText(String value, String fieldName) {
        String normalized = normalizeText(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeText(String value) {
        return TransportDeliveryAddressing.normalizeText(value);
    }
}
