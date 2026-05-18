package com.xa.mass.sdk.model;

import java.time.Instant;

public record TaskStageProjectionSnapshot(
        String taskId,
        String messageId,
        String stageName,
        long stageVersion,
        String stageStatus,
        String detail,
        Instant observedAt,
        Instant acceptedAt
) {
}
