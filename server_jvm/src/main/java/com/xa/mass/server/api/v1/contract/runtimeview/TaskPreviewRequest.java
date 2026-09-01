package com.xa.mass.server.api.v1.contract.runtimeview;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record TaskPreviewRequest(
        @Min(1)
        @Max(100)
        int sampleLimit
) {
}
