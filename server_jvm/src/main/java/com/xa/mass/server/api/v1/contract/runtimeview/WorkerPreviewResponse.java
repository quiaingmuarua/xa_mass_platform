package com.xa.mass.server.api.v1.contract.runtimeview;

import java.time.Instant;
import java.util.List;

public record WorkerPreviewResponse(
        String workerGroupId,
        int sampleLimit,
        int sampledCount,
        int returnedCount,
        int unreadableCount,
        Instant generatedAt,
        List<WorkerView> workers
) {
}
