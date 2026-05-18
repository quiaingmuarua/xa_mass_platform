package com.xa.mass.engine.command;

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
                createdAt,
                now
        );
    }
}
