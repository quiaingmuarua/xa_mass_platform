package com.xa.mass.transport.model;

import com.xa.mass.transport.packet.TransportPacket;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Adapter ingress envelope carrying a decoded worker frame into the runtime.
 *
 * <p>Transport owns frame shape and adapter identity here. Task result
 * correlation and lifecycle validation remain engine-owned.</p>
 */
public final class InboundEnvelope {

    private final String envelopeId;
    private final String adapterId;
    private final String sourceWorkerId;
    private final String routeKey;
    private final String connectionId;
    private final TransportPacket payload;
    private final Map<String, String> correlation;
    private final long receivedAtEpochMillis;

    public InboundEnvelope(String envelopeId,
                           String adapterId,
                           String sourceWorkerId,
                           String routeKey,
                           String connectionId,
                           TransportPacket payload,
                           Map<String, String> correlation,
                           long receivedAtEpochMillis) {
        this.envelopeId = requireText(envelopeId, "envelopeId");
        this.adapterId = requireAdapterId(adapterId);
        this.sourceWorkerId = requireText(sourceWorkerId, "sourceWorkerId");
        this.routeKey = TransportDeliveryAddressing.normalizeRouteKey(routeKey);
        this.connectionId = TransportDeliveryAddressing.normalizeText(connectionId);
        this.payload = Objects.requireNonNull(payload, "payload");
        this.correlation = normalizeCorrelation(correlation);
        this.receivedAtEpochMillis = Math.max(0L, receivedAtEpochMillis);
    }

    public String getEnvelopeId() {
        return envelopeId;
    }

    public String getAdapterId() {
        return adapterId;
    }

    public String getSourceWorkerId() {
        return sourceWorkerId;
    }

    public String getRouteKey() {
        return routeKey;
    }

    public String getConnectionId() {
        return connectionId;
    }

    public TransportPacket getPayload() {
        return payload;
    }

    public Map<String, String> getCorrelation() {
        return correlation;
    }

    public long getReceivedAtEpochMillis() {
        return receivedAtEpochMillis;
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

    private static Map<String, String> normalizeCorrelation(Map<String, String> correlation) {
        if (correlation == null || correlation.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        correlation.forEach((key, value) -> {
            String normalizedKey = TransportDeliveryAddressing.normalizeText(key);
            String normalizedValue = TransportDeliveryAddressing.normalizeText(value);
            if (normalizedKey != null && normalizedValue != null) {
                normalized.put(normalizedKey, normalizedValue);
            }
        });
        return Map.copyOf(normalized);
    }
}
