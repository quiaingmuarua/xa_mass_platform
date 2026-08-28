package com.xa.mass.kernel.worker;

import java.util.Map;

/** Semantic Mechanism port for bounded Worker execution result events. */
public interface WorkerExecutionResultEvents {

    void onTaskSucceeded(
            String workerGroupId,
            Map<String, WorkerLeaseReference> leasesByWorkerId,
            long observedAtMillis
    );

    void onTaskFailed(
            String workerGroupId,
            Map<String, WorkerLeaseReference> leasesByWorkerId,
            long observedAtMillis
    );
}
