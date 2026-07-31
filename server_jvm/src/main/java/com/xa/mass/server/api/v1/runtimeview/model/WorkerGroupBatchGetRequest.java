package com.xa.mass.server.api.v1.runtimeview.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record WorkerGroupBatchGetRequest(
        @NotNull
        @Size(min = 1, max = 20)
        List<@NotBlank String> workerGroupIds
) {
    public WorkerGroupBatchGetRequest {
        workerGroupIds = workerGroupIds == null
                ? null
                : List.copyOf(workerGroupIds);
    }
}
