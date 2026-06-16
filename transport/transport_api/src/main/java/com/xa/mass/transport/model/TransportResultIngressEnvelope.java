package com.xa.mass.transport.model;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Opaque transport carrier for worker result ingress.
 *
 * <p>Transport may queue, buffer, shard, and diagnose this envelope. It must
 * not decode {@code payload} or {@code correlation} to decide task result
 * correctness.</p>
 */
public final class TransportResultIngressEnvelope {

    private final String ingressId;
    private final String payload;
    private final String correlation;
    private final String partitionKey;
    private final Map<String, String> diagnostics;
    private final long receivedAtEpochMillis;

    public TransportResultIngressEnvelope(String ingressId,
                                          String payload,
                                          String correlation,
                                          String partitionKey,
                                          Map<String, String> diagnostics,
                                          long receivedAtEpochMillis) {
        this.ingressId = normalizeOrGenerate(ingressId);
        this.payload = requireText(payload, "payload");
        this.correlation = normalize(correlation);
        this.partitionKey = normalize(partitionKey);
        this.diagnostics = normalizeDiagnostics(diagnostics);
        this.receivedAtEpochMillis = receivedAtEpochMillis > 0L
                ? receivedAtEpochMillis
                : System.currentTimeMillis();
    }

    public static TransportResultIngressEnvelope received(String payload,
                                                          String correlation,
                                                          String partitionKey,
                                                          Map<String, String> diagnostics) {
        return new TransportResultIngressEnvelope(
                null,
                payload,
                correlation,
                partitionKey,
                diagnostics,
                System.currentTimeMillis()
        );
    }

    public String getIngressId() {
        return ingressId;
    }

    public String getPayload() {
        return payload;
    }

    public String getCorrelation() {
        return correlation;
    }

    public String getPartitionKey() {
        return partitionKey;
    }

    public Map<String, String> getDiagnostics() {
        return diagnostics;
    }

    public String diagnostic(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return diagnostics.get(key.trim());
    }

    public long getReceivedAtEpochMillis() {
        return receivedAtEpochMillis;
    }

    private static String normalizeOrGenerate(String value) {
        String normalized = normalize(value);
        return normalized != null ? normalized : UUID.randomUUID().toString();
    }

    private static String requireText(String value, String fieldName) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static Map<String, String> normalizeDiagnostics(Map<String, String> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, String> normalized = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> entry : diagnostics.entrySet()) {
            String key = normalize(entry.getKey());
            String value = normalize(entry.getValue());
            if (key != null && value != null) {
                normalized.put(key, value);
            }
        }
        return Map.copyOf(normalized);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TransportResultIngressEnvelope that)) {
            return false;
        }
        return receivedAtEpochMillis == that.receivedAtEpochMillis
                && Objects.equals(ingressId, that.ingressId)
                && Objects.equals(payload, that.payload)
                && Objects.equals(correlation, that.correlation)
                && Objects.equals(partitionKey, that.partitionKey)
                && Objects.equals(diagnostics, that.diagnostics);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ingressId, payload, correlation, partitionKey, diagnostics, receivedAtEpochMillis);
    }
}
