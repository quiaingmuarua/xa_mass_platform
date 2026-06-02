package com.xa.mass.client.worker;

public record WorkerCommandAckResult(
        String code,
        boolean accepted,
        String previousStatus,
        String currentStatus,
        String reason,
        WorkerCommand command
) {
}
