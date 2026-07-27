package com.xa.mass.server.kernelbinding;

import com.xa.mass.kernel.KernelOperationNotImplementedException;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeResult;
import com.xa.mass.kernel.worker.redis.RedisWorkerResourceCatalog;
import java.util.List;
import java.util.Map;

public final class AssembledWorkerResourceCatalog
        implements WorkerResourceCatalog {

    private final HttpWorkerResourceCatalog controlProvider;
    private final RedisWorkerResourceCatalog readProvider;

    public AssembledWorkerResourceCatalog(
            HttpWorkerResourceCatalog controlProvider,
            RedisWorkerResourceCatalog readProvider
    ) {
        this.controlProvider = controlProvider;
        this.readProvider = readProvider;
    }

    @Override
    public WorkerRuntimeResult upsertWorkerGroup(
            WorkerGroupDescriptor descriptor
    ) {
        return controlProvider.upsertWorkerGroup(descriptor);
    }

    @Override
    public Map<String, WorkerGroupDescriptor> getWorkerGroupDescriptors(
            List<String> workerGroupIds
    ) {
        return readProvider.getWorkerGroupDescriptors(workerGroupIds);
    }

    @Override
    public Map<String, WorkerDescriptor> getWorkerDescriptors(
            String workerGroupId,
            List<String> workerIds
    ) {
        throw notImplemented("get_worker_descriptors");
    }

    @Override
    public WorkerRuntimeResult updateWorkerPlatformAttributes(
            String workerGroupId,
            String workerId,
            Map<String, Object> attributes
    ) {
        throw notImplemented("update_worker_platform_attributes");
    }

    private static KernelOperationNotImplementedException notImplemented(
            String operation
    ) {
        return new KernelOperationNotImplementedException(
                "WorkerResourceCatalog",
                operation
        );
    }
}
