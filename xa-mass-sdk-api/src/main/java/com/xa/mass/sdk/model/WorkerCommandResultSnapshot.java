package com.xa.mass.sdk.model;

public record WorkerCommandResultSnapshot(
        String code,
        boolean accepted,
        String previousStatus,
        String currentStatus,
        String reason,
        WorkerCommandSnapshot command
) {
}
