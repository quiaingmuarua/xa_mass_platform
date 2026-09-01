package com.xa.mass.server.api.v1.contract.worker;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record WorkerBatchPreparationRequest(
        @NotNull @Size(min = 1, max = 100)
        List<@NotNull @Valid WorkerPreparationRequest> workers
) {
    public WorkerBatchPreparationRequest {
        workers = workers == null ? null : List.copyOf(workers);
    }
}
