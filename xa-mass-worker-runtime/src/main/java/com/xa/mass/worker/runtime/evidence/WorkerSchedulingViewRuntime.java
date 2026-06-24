package com.xa.mass.worker.runtime.evidence;

import java.util.Optional;

/**
 * Read surface used to build engine-owned scheduling candidates.
 */
public interface WorkerSchedulingViewRuntime {

    Optional<WorkerGroupCapabilityView> workerGroupReadView(String groupId);

    boolean isWorkerDispatchEnabled(String workerId);

    boolean hasWorkerExclusiveLease(String workerId);

    WorkerLoadSnapshot getWorkerLoad(String workerId);
}
