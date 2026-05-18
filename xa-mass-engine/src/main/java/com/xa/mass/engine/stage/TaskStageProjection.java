package com.xa.mass.engine.stage;

import java.time.Instant;
import java.util.List;

public record TaskStageProjection(
        String taskId,
        String messageId,
        String stageName,
        long stageVersion,
        String stageStatus,
        String detail,
        Instant observedAt,
        Instant acceptedAt,
        List<TaskStageEvidence> recentEvidence
) {
}
