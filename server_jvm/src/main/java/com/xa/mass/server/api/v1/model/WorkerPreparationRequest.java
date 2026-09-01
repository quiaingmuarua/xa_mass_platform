package com.xa.mass.server.api.v1.model;

import com.xa.mass.server.worker.binding.WorkerTransportType;
import com.xa.mass.server.worker.identity.WorkerRegistrationKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record WorkerPreparationRequest(
        @Schema(defaultValue = "CLIENT_KEY")
        WorkerRegistrationKind workerKind,
        @NotNull WorkerTransportType transportType,
        @NotNull Map<String, Object> workerProperties
) {
    public WorkerPreparationRequest {
        workerKind = workerKind == null
                ? WorkerRegistrationKind.CLIENT_KEY
                : workerKind;
    }
}
