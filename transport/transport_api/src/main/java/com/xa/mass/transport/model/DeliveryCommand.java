package com.xa.mass.transport.model;

import com.xa.mass.transport.packet.TransportPacket;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Delivery-shaped command accepted by the transport executor.
 *
 * <p>The command carries assignment facts as delivery constraints only.
 * Transport must not interpret correlation fields as task lifecycle truth.</p>
 */
public final class DeliveryCommand {

    private final String commandId;
    private final String adapterId;
    private final String selectedWorkerId;
    private final String deliveryQueueKey;
    private final String targetTransportNodeId;
    private final String routeKey;
    private final String connectionToken;
    private final TransportPacket payload;
    private final Map<String, String> correlation;
    private final long deadlineEpochMillis;
    private final long createdAtEpochMillis;

    public DeliveryCommand(String commandId,
                           String adapterId,
                           String selectedWorkerId,
                           String deliveryQueueKey,
                           String targetTransportNodeId,
                           String routeKey,
                           String connectionToken,
                           TransportPacket payload,
                           Map<String, String> correlation,
                           long deadlineEpochMillis,
                           long createdAtEpochMillis) {
        this.commandId = requireText(commandId, "commandId");
        this.adapterId = requireAdapterId(adapterId);
        this.selectedWorkerId = requireText(selectedWorkerId, "selectedWorkerId");
        this.deliveryQueueKey = TransportDeliveryAddressing.normalizeText(deliveryQueueKey);
        this.targetTransportNodeId = TransportDeliveryAddressing.normalizeText(targetTransportNodeId);
        this.routeKey = TransportDeliveryAddressing.normalizeRouteKey(routeKey);
        this.connectionToken = TransportDeliveryAddressing.normalizeText(connectionToken);
        this.payload = Objects.requireNonNull(payload, "payload");
        this.correlation = normalizeCorrelation(correlation);
        this.deadlineEpochMillis = Math.max(0L, deadlineEpochMillis);
        this.createdAtEpochMillis = Math.max(0L, createdAtEpochMillis);
    }

    public String getCommandId() {
        return commandId;
    }

    public String getAdapterId() {
        return adapterId;
    }

    public String getSelectedWorkerId() {
        return selectedWorkerId;
    }

    public String getDeliveryQueueKey() {
        return deliveryQueueKey;
    }

    public String getTargetTransportNodeId() {
        return targetTransportNodeId;
    }

    public String getRouteKey() {
        return routeKey;
    }

    public String getConnectionToken() {
        return connectionToken;
    }

    public TransportPacket getPayload() {
        return payload;
    }

    public Map<String, String> getCorrelation() {
        return correlation;
    }

    public long getDeadlineEpochMillis() {
        return deadlineEpochMillis;
    }

    public long getCreatedAtEpochMillis() {
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
