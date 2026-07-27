package com.xa.mass.kernel.worker;

import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeResult;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public interface WorkerResourceCatalog {

    WorkerRuntimeResult upsertWorkerGroup(WorkerGroupDescriptor descriptor);

    Map<String, @Nullable WorkerGroupDescriptor> getWorkerGroupDescriptors(
            List<String> workerGroupIds
    );

    Map<String, @Nullable WorkerDescriptor> getWorkerDescriptors(
            String workerGroupId,
            List<String> workerIds
    );

    WorkerRuntimeResult updateWorkerPlatformAttributes(
            String workerGroupId,
            String workerId,
            Map<String, Object> attributes
    );
}
