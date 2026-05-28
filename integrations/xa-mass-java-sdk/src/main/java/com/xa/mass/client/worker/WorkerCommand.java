package com.xa.mass.client.worker;

import java.time.Instant;
import java.util.Map;

public record WorkerCommand(
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
}
