package com.xa.mass.sdk.model;

import java.time.Instant;
import java.util.Map;

public record TaskStageEvidenceRequest(
        String taskId,
        String messageId,
        String stageName,
        long stageVersion,
        String stageStatus,
        String detail,
        Instant observedAt,
        Map<String, Object> attributes
) {
    public TaskStageEvidenceRequest {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
