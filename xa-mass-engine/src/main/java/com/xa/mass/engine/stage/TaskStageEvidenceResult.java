package com.xa.mass.engine.stage;

public record TaskStageEvidenceResult(
        TaskStageEvidenceStatus status,
        String taskId,
        String messageId,
        String stageName,
        long stageVersion,
        boolean projectionChanged,
        TaskStageProjection projection,
        String reason
) {

    public boolean success() {
        return status == TaskStageEvidenceStatus.ACCEPTED
                || status == TaskStageEvidenceStatus.IDEMPOTENT;
    }
}
