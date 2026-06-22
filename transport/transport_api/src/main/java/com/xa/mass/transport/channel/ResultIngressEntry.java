package com.xa.mass.transport.channel;

import java.util.Objects;

/**
 * One transport result-ingress queue entry.
 */
public record ResultIngressEntry(String partitionKey,
                                 ResultIngressMessage message,
                                 ResultIngressDiagnostics diagnostics) {

    public ResultIngressEntry {
        partitionKey = requireText(partitionKey, "partitionKey");
        message = Objects.requireNonNull(message, "message");
        diagnostics = diagnostics == null ? ResultIngressDiagnostics.empty() : diagnostics;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
