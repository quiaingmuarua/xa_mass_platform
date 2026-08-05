package com.xa.mass.server.api.v1.model;

import com.xa.mass.server.workerbinding.WorkerTransportType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record WorkerBindingRequest(
        @NotBlank String clientWorkerKey,
        @NotNull WorkerTransportType transportType,
        @NotNull Map<String, Object> workerProperties
) {
}
