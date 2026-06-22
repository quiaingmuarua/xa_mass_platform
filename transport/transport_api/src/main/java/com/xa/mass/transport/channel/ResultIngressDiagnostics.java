package com.xa.mass.transport.channel;

import java.util.Map;

/**
 * Bounded result-ingress diagnostics. These values are not task-result truth.
 */
public record ResultIngressDiagnostics(Map<String, String> values) {

    public ResultIngressDiagnostics {
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    public static ResultIngressDiagnostics empty() {
        return new ResultIngressDiagnostics(Map.of());
    }

    public String get(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return values.get(key);
    }
}
