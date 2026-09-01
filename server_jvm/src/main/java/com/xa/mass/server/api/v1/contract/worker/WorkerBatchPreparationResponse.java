package com.xa.mass.server.api.v1.contract.worker;

import com.xa.mass.server.worker.preparation.WorkerPreparationService;
import java.util.List;

public record WorkerBatchPreparationResponse(
        List<WorkerPreparationResponse> workers
) {

    public WorkerBatchPreparationResponse {
        workers = List.copyOf(workers);
    }

    public static WorkerBatchPreparationResponse from(
            List<WorkerPreparationService.PreparedWorker> prepared
    ) {
        return new WorkerBatchPreparationResponse(prepared.stream()
                .map(WorkerPreparationResponse::from)
                .toList());
    }
}
