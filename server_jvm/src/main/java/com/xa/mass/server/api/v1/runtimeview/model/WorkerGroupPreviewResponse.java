package com.xa.mass.server.api.v1.runtimeview.model;

import java.time.Instant;
import java.util.List;

public record WorkerGroupPreviewResponse(
        int sampleLimit,
        int sampledCount,
        int returnedCount,
        int unreadableCount,
        Instant generatedAt,
        List<WorkerGroupView> workerGroups
) {
}
