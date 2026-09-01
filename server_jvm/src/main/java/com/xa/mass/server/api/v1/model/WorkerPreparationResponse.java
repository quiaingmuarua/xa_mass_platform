package com.xa.mass.server.api.v1.model;

import com.xa.mass.server.worker.preparation.WorkerPreparationService;

public record WorkerPreparationResponse(
        String workerId,
        String transportType,
        String endpointUri
) {

    public static WorkerPreparationResponse from(
            WorkerPreparationService.PreparedWorker prepared
    ) {
        return new WorkerPreparationResponse(
                prepared.workerId(),
                prepared.transportType().name(),
                prepared.endpointUri().toString()
        );
    }
}
