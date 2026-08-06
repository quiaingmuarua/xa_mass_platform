package com.xa.mass.server.api.v1.model;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record WorkerIdentityRegisterRequest(
        @NotNull Map<String, Object> workerProperties
) {
}
