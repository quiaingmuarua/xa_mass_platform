package com.xa.mass.sdk.model;

public record TaskStageEvidenceSnapshot(
        String status,
        String taskId,
        String messageId,
        String stageName,
        long stageVersion,
        boolean accepted,
        boolean projectionChanged,
        String reason,
        TaskStageProjectionSnapshot projection
) {
}
