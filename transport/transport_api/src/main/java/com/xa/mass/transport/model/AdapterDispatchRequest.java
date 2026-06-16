package com.xa.mass.transport.model;

/**
 * Final-hop adapter request for one assigned task dispatch.
 */
public final class AdapterDispatchRequest {

    private final String deliveryId;
    private final String deliveryBucketId;
    private final String selectedWorkerId;
    private final String payload;
    private final String correlationRef;
    private final long createdAtEpochMillis;

    public AdapterDispatchRequest(String deliveryId,
                                  String deliveryBucketId,
                                  String selectedWorkerId,
                                  String payload,
                                  String correlationRef,
                                  long createdAtEpochMillis) {
        this.deliveryId = requireText(deliveryId, "deliveryId");
        this.deliveryBucketId = requireText(deliveryBucketId, "deliveryBucketId");
        this.selectedWorkerId = requireText(selectedWorkerId, "selectedWorkerId");
        this.payload = requireText(payload, "payload");
        this.correlationRef = requireText(correlationRef, "correlationRef");
        this.createdAtEpochMillis = Math.max(0L, createdAtEpochMillis);
    }

    public String deliveryId() {
        return deliveryId;
    }

    public String deliveryBucketId() {
        return deliveryBucketId;
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
        String normalized = TransportDeliveryAddressing.normalizeText(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
