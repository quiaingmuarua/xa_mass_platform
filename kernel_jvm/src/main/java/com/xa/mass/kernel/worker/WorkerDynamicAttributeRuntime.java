package com.xa.mass.kernel.worker;

import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeResult;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeStatus;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public interface WorkerDynamicAttributeRuntime {

    Map<String, WorkerRuntimeResult> updateWorkerDynamicAttributes(
            String workerGroupId,
            String workerId,
            Map<String, Object> updates,
            long observedAtMillis
    );

    Map<String, DynamicAttributeReadResult> getWorkerDynamicAttributeValues(
            String workerGroupId,
            String attributeName,
            List<String> workerIds
    );

    boolean supportsCandidateQuery(
            String attributeName,
            Map<String, Object> operatorRule
    );

    List<String> queryCandidateWorkerIds(
            String workerGroupId,
            String attributeName,
            Map<String, Object> operatorRule,
            int limit
    );

    record DynamicAttributeReadResult(
            WorkerRuntimeStatus status,
            @Nullable Object value,
            @Nullable Long observedAtMillis,
            @Nullable String reason
    ) {
        public DynamicAttributeReadResult {
            Objects.requireNonNull(status, "status");
        }
    }
}
