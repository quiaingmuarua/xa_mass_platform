package com.xa.mass.worker.runtime.command;

import java.time.Instant;
import java.util.Map;

public record WorkerCommandRecord(
        String commandId,
        String workerId,
        String commandType,
        WorkerCommandStatus status,
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

    static WorkerCommandRecord requested(WorkerCommandRequest request, Instant now) {
        return new WorkerCommandRecord(
                request.commandId(),
                request.workerId(),
                request.commandType(),
                WorkerCommandStatus.REQUESTED,
                request.requester(),
                request.reason(),
                request.idempotencyKey(),
                request.deadlineEpochMillis(),
                request.payload(),
                request.reason(),
                0,
                null,
                now,
                now
        );
    }

    WorkerCommandRecord withStatus(WorkerCommandStatus nextStatus, String nextReason, Instant now) {
        return new WorkerCommandRecord(
                commandId,
                workerId,
                commandType,
                nextStatus,
                requester,
                reason,
                idempotencyKey,
                deadlineEpochMillis,
                payload,
                nextReason,
                deliveryAttemptCount,
                lastDeliveryAttemptAt,
                createdAt,
                now
        );
    }

    WorkerCommandRecord withDeliveryAttempt(String attemptReason, Instant now) {
        return new WorkerCommandRecord(
                commandId,
                workerId,
                commandType,
                status,
                requester,
                reason,
                idempotencyKey,
                deadlineEpochMillis,
                payload,
                attemptReason,
                deliveryAttemptCount + 1,
                now,
                createdAt,
                now
        );
    }

    WorkerCommandRecord withStatusReason(String nextStatusReason, Instant now) {
        return new WorkerCommandRecord(
                commandId,
                workerId,
                commandType,
                status,
                requester,
                reason,
                idempotencyKey,
                deadlineEpochMillis,
                payload,
                nextStatusReason,
                deliveryAttemptCount,
                lastDeliveryAttemptAt,
                createdAt,
                now
        );
    }
}
