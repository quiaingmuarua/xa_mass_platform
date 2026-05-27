package com.xa.mass.engine.worker;

import com.xa.mass.base.model.Worker;
import com.xa.mass.engine.load.WorkerLoadSnapshot;

import java.util.Optional;

/**
 * Read surface used to build engine-owned scheduling candidates.
 */
public interface WorkerSchedulingViewRuntime {

    Optional<WorkerGroupRecord> workerGroupReadView(String groupId);

    WorkerReachabilityState getWorkerReachability(String workerId);

    boolean isWorkerDispatchEnabled(Worker worker);

    boolean hasWorkerExclusiveLease(String workerId);

    WorkerLoadSnapshot getWorkerLoad(String workerId);
}
