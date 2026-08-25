package com.xa.mass.server.api.v1.runtimeview.model;

import java.time.Instant;
import java.util.List;

public record TaskPreviewResponse(
        int sampleLimit,
        Instant generatedAt,
        List<TaskPreviewEntry> entries
) {
}
