package com.xa.mass.kernel.worker;

import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeResult;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public interface WorkerPropertyIndexRuntime {

    int MAX_INDEXED_PROPERTY_READ_LIMIT = 100;

    Map<String, WorkerRuntimeResult> updateIndexedProperties(
            String workerGroupId,
            String workerId,
            Map<String, @Nullable Object> updates
    );

    Map<String, Object> loadIndexedPropertyValues(
            String workerGroupId,
            String indexField,
            List<String> workerIds
    );
}
