package com.xa.mass.scenariorpc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ScenarioRpcItem(
        String messageId,
        Map<String, Object> payload
) {
    public ScenarioRpcItem {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must be non-blank");
        }
        Objects.requireNonNull(payload, "payload");
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
