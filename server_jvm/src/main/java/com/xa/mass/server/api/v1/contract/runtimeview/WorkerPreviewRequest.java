package com.xa.mass.server.api.v1.contract.runtimeview;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record WorkerPreviewRequest(
        @NotNull
        @Min(1)
        @Max(100)
        Integer sampleLimit,
        Object filter
) {
}
