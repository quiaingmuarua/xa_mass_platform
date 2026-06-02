package com.xa.mass.sdk.model;

import java.util.Map;

public record WorkerCommandSubmitRequest(
        String commandId,
        String workerId,
        String commandType,
        String requester,
        String reason,
        String idempotencyKey,
        Long deadlineEpochMillis,
        Map<String, Object> payload
) {
    public WorkerCommandSubmitRequest {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
