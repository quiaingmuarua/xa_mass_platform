package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.TransportDeliveryAddressing;

/**
 * Group-level observation facts for delivery outcome and failure events.
 */
public record DeliveryObservationGroupContext(String adapterId,
                                              String deliveryQueueKey,
                                              String targetTransportNodeId,
                                              long occurredAtEpochMillis) {

    public DeliveryObservationGroupContext {
        adapterId = TransportDeliveryAddressing.normalizeAdapterId(adapterId);
        deliveryQueueKey = normalizeText(deliveryQueueKey);
        targetTransportNodeId = normalizeText(targetTransportNodeId);
        occurredAtEpochMillis = Math.max(0L, occurredAtEpochMillis);
    }

    public static DeliveryObservationGroupContext now(String adapterId,
                                                      String deliveryQueueKey,
                                                      String targetTransportNodeId) {
        return new DeliveryObservationGroupContext(
                adapterId,
                deliveryQueueKey,
                targetTransportNodeId,
                System.currentTimeMillis()
        );
    }

    private static String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
