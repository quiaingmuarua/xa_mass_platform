package com.xa.mass.server.api.v1.contract.worker;

import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public record WorkerPropertiesPatchRequest(
        @NotNull Map<String, @Nullable Object> properties
) {
}
