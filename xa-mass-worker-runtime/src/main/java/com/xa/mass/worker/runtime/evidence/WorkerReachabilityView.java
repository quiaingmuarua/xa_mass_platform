package com.xa.mass.worker.runtime.evidence;

/**
 * Cross-module read seam for worker-runtime reachability evidence consumed by
 * scheduling.
 */
@FunctionalInterface
public interface WorkerReachabilityView {

    WorkerReachabilityState getWorkerReachability(String workerId);

    default boolean isWorkerReachable(String workerId) {
        return getWorkerReachability(workerId) == WorkerReachabilityState.ONLINE;
    }

    static WorkerReachabilityView permissive() {
        return workerId -> WorkerReachabilityState.ONLINE;
    }
}
