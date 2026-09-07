package com.xa.mass.server.api.v1.contract.worker;

import com.xa.mass.server.worker.endpoint.WorkerTransportType;
import com.xa.mass.server.worker.identity.WorkerRegistrationKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record WorkerPreparationRequest(
        @Schema(defaultValue = "CLIENT_KEY")
        WorkerRegistrationKind workerKind,
        @NotNull WorkerTransportType transportType,
        @Schema(description = "Input for the Server-owned identity policy. Only registration coordinates "
                + "are consumed; Prepare does not store Worker Properties. Matching facts arrive "
                + "through Adapter observations after connection.")
        @NotNull Map<String, Object> workerProperties
) {
    public WorkerPreparationRequest {
        workerKind = workerKind == null
                ? WorkerRegistrationKind.CLIENT_KEY
                : workerKind;
    }
}
