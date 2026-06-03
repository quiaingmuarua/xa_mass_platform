package com.xa.mass.engine.runtime.scheduling;

/**
 * Behavior-neutral resolved Scheduling Plane snapshot for one task.
 */
public record SchedulingPlaneResolution(
        TaskDispatchIntent dispatchIntent,
        ResolvedTaskSchedulingPolicy taskSchedulingPolicy,
        ResolvedWorkerSchedulingPolicy workerSchedulingPolicy
) {

    public SchedulingPlaneResolution {
        if (dispatchIntent == null) {
            throw new IllegalArgumentException("dispatchIntent is required");
        }
        if (taskSchedulingPolicy == null) {
            throw new IllegalArgumentException("taskSchedulingPolicy is required");
        }
        if (workerSchedulingPolicy == null) {
            throw new IllegalArgumentException("workerSchedulingPolicy is required");
        }
    }
}
