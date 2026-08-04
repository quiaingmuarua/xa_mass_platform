package com.xa.mass.server.api.v1.model;

import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public record WorkerIndexedPropertiesPatchRequest(
        @NotNull Map<String, @Nullable Object> updates
) {
}
