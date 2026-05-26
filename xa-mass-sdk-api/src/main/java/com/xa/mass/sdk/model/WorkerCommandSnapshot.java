package com.xa.mass.sdk.model;

import java.time.Instant;
import java.util.Map;

public record WorkerCommandSnapshot(
        String commandId,
        String workerId,
        String commandType,
        String status,
        String requester,
        String reason,
        String idempotencyKey,
        Long deadlineEpochMillis,
        Map<String, Object> payload,
        String statusReason,
        int deliveryAttemptCount,
        Instant lastDeliveryAttemptAt,
        Instant createdAt,
        Instant updatedAt
) {
    public WorkerCommandSnapshot {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
