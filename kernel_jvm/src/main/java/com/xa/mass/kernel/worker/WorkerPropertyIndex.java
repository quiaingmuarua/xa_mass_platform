package com.xa.mass.kernel.worker;

import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeResult;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** One configured scheduling-property index. */
public interface WorkerPropertyIndex {
    WorkerRuntimeResult update(
            String workerGroupId,
            String workerId,
            @Nullable Object value
    );

    Map<String, Object> load(
            String workerGroupId,
            List<String> workerIds
    );
}
