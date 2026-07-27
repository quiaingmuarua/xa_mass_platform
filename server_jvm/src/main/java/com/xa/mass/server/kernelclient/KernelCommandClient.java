package com.xa.mass.server.kernelclient;

import com.xa.mass.server.api.v1.model.CommandResultResponse;
import com.xa.mass.server.api.v1.model.TaskCreateRequest;
import com.xa.mass.server.api.v1.model.WorkerGroupUpsertRequest;
import com.xa.mass.server.api.v1.model.WorkerUpsertRequest;

public interface KernelCommandClient {

    KernelResponse<CommandResultResponse> upsertWorkerGroup(
            String workerGroupId,
            WorkerGroupUpsertRequest request
    );

    KernelResponse<CommandResultResponse> upsertWorker(
            String workerGroupId,
            String workerId,
            WorkerUpsertRequest request
    );

    KernelResponse<CommandResultResponse> createTask(TaskCreateRequest request);

    KernelResponse<CommandResultResponse> approveTask(String taskId);

    KernelResponse<CommandResultResponse> closeTask(String taskId);

    boolean isHealthy();
}
