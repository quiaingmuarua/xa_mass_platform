package com.xa.mass.server.api.v1.runtimeview.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record WorkerGroupPreviewRequest(
        @Min(1) @Max(100) int sampleLimit
) {
}
