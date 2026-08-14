package com.xa.mass.scenariorpc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ScenarioRpcResult(
        String workerGroupId,
        String messageId,
        String eventCode,
        Map<String, Object> input,
        Map<String, Object> result
) {
    public ScenarioRpcResult {
        Objects.requireNonNull(workerGroupId, "workerGroupId");
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(eventCode, "eventCode");
        input = immutable(input, "input");
        result = immutable(result, "result");
    }

    private static Map<String, Object> immutable(
            Map<String, Object> values,
            String name
    ) {
        Objects.requireNonNull(values, name);
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
