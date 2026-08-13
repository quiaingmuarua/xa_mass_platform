package com.xa.mass.integration.workercapability.process;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

record RpcSeed(
        int sequence,
        int lineNumber,
        String messageId,
        String eventCode,
        Map<String, Object> payload
) {

    RpcSeed {
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        if (lineNumber <= 0) {
            throw new IllegalArgumentException("lineNumber must be positive");
        }
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(eventCode, "eventCode");
        Objects.requireNonNull(payload, "payload");
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
