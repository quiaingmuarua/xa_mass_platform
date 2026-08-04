package com.xa.mass.kernel.worker;

import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeResult;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public interface WorkerResourceCatalog {

    int MAX_WORKER_DESCRIPTOR_SAMPLE_LIMIT = 100;

    WorkerRuntimeResult upsertWorkerGroup(WorkerGroupDescriptor descriptor);

    Map<String, @Nullable WorkerGroupDescriptor> getWorkerGroupDescriptors(
            List<String> workerGroupIds
    );

    Map<String, @Nullable WorkerDescriptor> getWorkerDescriptors(
            String workerGroupId,
            List<String> workerIds
    );

    Map<String, @Nullable WorkerDescriptor> sampleWorkerDescriptors(
            String workerGroupId,
            int sampleLimit
    );

    WorkerRuntimeResult patchWorkerPlatformProperties(
            String workerGroupId,
            String workerId,
            Map<String, @Nullable Object> properties
    );
}
