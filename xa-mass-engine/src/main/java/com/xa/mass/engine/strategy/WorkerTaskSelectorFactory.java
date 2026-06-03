package com.xa.mass.engine.strategy;

import com.xa.mass.base.model.Task;
import com.xa.mass.engine.runtime.scheduling.ResolvedWorkerSchedulingPolicy;
import com.xa.mass.engine.runtime.scheduling.SchedulingPlaneResolution;
import com.xa.mass.engine.runtime.scheduling.SchedulingPlaneResolver;
import com.xa.mass.worker.runtime.candidate.WorkerTaskSelector;

import java.util.Objects;

/**
 * Engine-side adapter from resolved worker scheduling policy to worker runtime
 * selector input.
 */
public final class WorkerTaskSelectorFactory {

    private static final SchedulingPlaneResolver DEFAULT_RESOLVER = new DefaultSchedulingPlaneResolver();

    private WorkerTaskSelectorFactory() {
    }

    public static WorkerTaskSelector fromTask(Task task) {
        SchedulingPlaneResolution resolution = DEFAULT_RESOLVER.resolve(task);
        return fromPolicy(resolution.workerSchedulingPolicy());
    }

    public static WorkerTaskSelector fromPolicy(ResolvedWorkerSchedulingPolicy workerPolicy) {
        ResolvedWorkerSchedulingPolicy resolvedPolicy = Objects.requireNonNull(workerPolicy, "workerPolicy");
        return new WorkerTaskSelector(
                resolvedPolicy.taskId(),
                resolvedPolicy.workerGroupIds(),
                resolvedPolicy.adapterNodeId(),
                resolvedPolicy.targetWorkerId(),
                resolvedPolicy.routeBucketKeys()
        );
    }
}
