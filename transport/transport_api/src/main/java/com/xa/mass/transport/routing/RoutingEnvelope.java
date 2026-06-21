package com.xa.mass.transport.routing;

import java.util.Map;
import java.util.Objects;

/**
 * Minimal queue/process-boundary carrier.
 *
 * <p>Payload is opaque to non-target owners. Diagnostics are bounded debug
 * facts and must not participate in routing, retry, lifecycle, or worker
 * correctness decisions.</p>
 */
public record RoutingEnvelope(String envelopeId,
                              RoutingTarget target,
                              String payload,
                              Map<String, String> diagnostics,
                              long createdAtEpochMillis) {

    public RoutingEnvelope {
        envelopeId = requireText(envelopeId, "envelopeId");
        target = Objects.requireNonNull(target, "target");
        payload = requireText(payload, "payload");
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
        createdAtEpochMillis = Math.max(0L, createdAtEpochMillis);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
