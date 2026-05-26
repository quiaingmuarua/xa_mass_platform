package com.xa.mass.api.auth.usage;

import java.time.Instant;

public record ApiUsageLedgerRecord(
        String usageId,
        String keyId,
        String principalId,
        String userId,
        String project,
        String eventCode,
        ApiUsageOperation operation,
        String taskId,
        String messageId,
        String requestId,
        long units,
        ApiUsageStatus status,
        String failureReason,
        Integer failureStatus,
        Instant createdAt
) {
}
