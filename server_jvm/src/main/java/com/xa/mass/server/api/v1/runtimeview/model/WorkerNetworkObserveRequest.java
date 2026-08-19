package com.xa.mass.server.api.v1.runtimeview.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record WorkerNetworkObserveRequest(
        @NotNull
        @Size(min = 1, max = 100)
        List<@NotBlank String> workerIds
) {
    public WorkerNetworkObserveRequest {
        workerIds = workerIds == null ? null : List.copyOf(workerIds);
    }
}
