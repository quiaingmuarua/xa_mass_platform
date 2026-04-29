package com.xa.mass.transport.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Transport-owned carrier for one dispatch payload moving through runtime
 * queues, direct-send paths, and adapter dispatch channels.
 */
public final class TransportDispatchEnvelope {

    private final String deliveryId;
    private final String adapterId;
    private final String routeKey;
    private final String correlationKey;
    private final TaskDispatchItem payload;
    private final long createdAtEpochMillis;

    public TransportDispatchEnvelope(String deliveryId,
                                     String adapterId,
                                     String routeKey,
                                     String correlationKey,
                                     TaskDispatchItem payload,
                                     long createdAtEpochMillis) {
        this.deliveryId = requireText(deliveryId, "deliveryId");
        this.adapterId = TransportDeliveryAddressing.normalizeAdapterId(adapterId);
        this.routeKey = TransportDeliveryAddressing.normalizeRouteKey(routeKey);
        this.correlationKey = TransportDeliveryAddressing.normalizeText(correlationKey);
        this.payload = Objects.requireNonNull(payload, "payload");
        this.createdAtEpochMillis = createdAtEpochMillis;
    }

    public static TransportDispatchEnvelope create(String adapterId,
                                                   String routeKey,
                                                   String correlationKey,
                                                   TaskDispatchItem payload,
                                                   long createdAtEpochMillis) {
        return new TransportDispatchEnvelope(
                UUID.randomUUID().toString(),
                adapterId,
                routeKey,
                correlationKey,
                payload,
                createdAtEpochMillis
        );
    }

    public String getDeliveryId() {
        return deliveryId;
    }

    public String getAdapterId() {
        return adapterId;
    }

    public String getRouteKey() {
        return routeKey;
    }

    public String getCorrelationKey() {
        return correlationKey;
    }

    public TaskDispatchItem getPayload() {
        return payload;
    }

    public long getCreatedAtEpochMillis() {
        return createdAtEpochMillis;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

}
