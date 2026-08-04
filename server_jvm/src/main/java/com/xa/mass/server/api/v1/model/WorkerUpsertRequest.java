package com.xa.mass.server.api.v1.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record WorkerUpsertRequest(
        @NotBlank String endpointManagerId,
        @NotNull Map<String, Object> workerProperties
) {
    public WorkerUpsertRequest {
        if (workerProperties != null) {
            workerProperties = Collections.unmodifiableMap(
                    new LinkedHashMap<>(workerProperties)
            );
        }
    }
}
