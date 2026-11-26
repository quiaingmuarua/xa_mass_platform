package com.xa.mass.server.api.v1.contract.worker;

public record WorkerGroupDeleteResponse(
        String workerGroupId,
        String status,
        int removedWorkers
) {
}
