package com.xa.mass.server.api.v1.contract.worker;

public record WorkerGroupRegisterResponse(
        String workerGroupId,
        String taskId,
        String status
) {
}
