package com.xa.mass.server.api.v1.contract.worker;

public record WorkerDeleteResponse(
        String workerGroupId,
        String workerId,
        String status
) {
}
