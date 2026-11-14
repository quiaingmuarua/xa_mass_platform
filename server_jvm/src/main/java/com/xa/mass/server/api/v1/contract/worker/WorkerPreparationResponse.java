package com.xa.mass.server.api.v1.contract.worker;

import com.xa.mass.server.worker.preparation.WorkerPreparationService;

@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkerPreparationResponse(
        String status,
        String workerId,
        String transportType,
        String endpointUri,
        Long cooldownUntilMillis
) {

    public static WorkerPreparationResponse from(
            WorkerPreparationService.PreparationResult prepared
    ) {
        return new WorkerPreparationResponse(
                prepared.status(),
                prepared.workerId(),
                prepared.transportType() == null ? null : prepared.transportType().name(),
                prepared.endpointUri() == null ? null : prepared.endpointUri().toString(),
                prepared.cooldownUntilMillis()
        );
    }
}
