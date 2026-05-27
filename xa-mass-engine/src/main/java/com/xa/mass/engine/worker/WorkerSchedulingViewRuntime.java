package com.xa.mass.engine.worker;

import com.xa.mass.runtime.worker.WorkerLoadSnapshot;
import com.xa.mass.runtime.worker.WorkerReachabilityState;

import java.util.Optional;

/**
 * Read surface used to build engine-owned scheduling candidates.
 */
public interface WorkerSchedulingViewRuntime {

    Optional<WorkerGroupRecord> workerGroupReadView(String groupId);

    WorkerReachabilityState getWorkerReachability(String workerId);

    boolean isWorkerDispatchEnabled(String workerId);

    boolean hasWorkerExclusiveLease(String workerId);

    WorkerLoadSnapshot getWorkerLoad(String workerId);
}
