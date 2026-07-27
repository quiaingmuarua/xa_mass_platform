package com.xa.mass.server.kernelbinding;

import com.xa.mass.kernel.KernelOperationNotImplementedException;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HttpWorkerResourceCatalog
        implements WorkerResourceCatalog {

    private final PythonKernelHttpTransport transport;

    public HttpWorkerResourceCatalog(PythonKernelHttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public WorkerRuntimeResult upsertWorkerGroup(
            WorkerGroupDescriptor descriptor
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attributes", descriptor.attributes());
        body.put("eventCodes", descriptor.eventCodes());
        body.put(
                "itemAllocationFields",
                descriptor.itemAllocationFields()
        );
        return HttpWorkerRuntime.result(transport.put(
                "/worker-groups/{workerGroupId}",
                body,
                descriptor.workerGroupId()
        ));
    }

    @Override
    public Map<String, WorkerGroupDescriptor> getWorkerGroupDescriptors(
            List<String> workerGroupIds
    ) {
        throw notImplemented("get_worker_group_descriptors");
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
