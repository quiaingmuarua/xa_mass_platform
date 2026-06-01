package com.xa.mass.client.worker.handler;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record WorkerResult(
        boolean success,
        String detail,
        String errorCode,
        Map<String, Object> output
) {
    public WorkerResult {
        output = output == null || output.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(output));
    }

    public static WorkerResult success(Map<String, Object> output) {
        return new WorkerResult(true, null, null, output);
    }

    public static WorkerResult success(String detail, Map<String, Object> output) {
        return new WorkerResult(true, detail, null, output);
    }

    public static WorkerResult failure(String errorCode, String detail) {
        return new WorkerResult(false, detail, errorCode, Map.of());
    }

    public static WorkerResult failure(String errorCode, String detail, Map<String, Object> output) {
        return new WorkerResult(false, detail, errorCode, output);
    }
}
