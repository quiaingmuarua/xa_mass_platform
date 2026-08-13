package com.xa.mass.integration.workercapability.process;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record RpcResult(
        String workerGroupId,
        String messageId,
        String eventCode,
        Map<String, Object> input,
        Map<String, Object> result
) {

    public RpcResult {
        workerGroupId = requireText(workerGroupId, "workerGroupId");
        messageId = requireText(messageId, "messageId");
        eventCode = requireText(eventCode, "eventCode");
        input = immutableCopy(input, "input");
        result = immutableCopy(result, "result");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Map<String, Object> immutableCopy(
            Map<String, Object> value,
            String name
    ) {
        Objects.requireNonNull(value, name);
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
