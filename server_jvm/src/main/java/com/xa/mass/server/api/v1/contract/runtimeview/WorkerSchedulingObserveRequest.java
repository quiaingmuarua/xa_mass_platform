package com.xa.mass.server.api.v1.contract.runtimeview;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record WorkerSchedulingObserveRequest(
        @NotNull
        @Size(min = 1, max = 100)
        List<@NotBlank String> workerIds
) {
    public WorkerSchedulingObserveRequest {
        workerIds = workerIds == null ? null : List.copyOf(workerIds);
    }
}
