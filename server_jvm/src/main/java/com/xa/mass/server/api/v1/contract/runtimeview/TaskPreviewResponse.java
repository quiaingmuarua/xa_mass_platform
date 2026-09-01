package com.xa.mass.server.api.v1.contract.runtimeview;

import java.time.Instant;
import java.util.List;

public record TaskPreviewResponse(
        int sampleLimit,
        Instant generatedAt,
        List<TaskPreviewEntry> entries
) {
}
