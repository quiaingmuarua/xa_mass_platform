package com.xa.mass.engine.resource;

import com.xa.mass.base.model.Task;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy.WorkerResourceMode;
import com.xa.mass.engine.runtime.scheduling.SchedulingPlaneResolver;
import com.xa.mass.engine.strategy.DefaultSchedulingPlaneResolver;

import java.util.Objects;

/**
 * Default resource usage semantics.
 *
 * <p>Resource usage is driven by resolved worker resource mode. Legacy task
 * fields may still feed preset resolution, but this policy does not interpret
 * them directly.</p>
 */
public final class DefaultWorkerDispatchResourcePolicy implements WorkerDispatchResourcePolicy {

    private final SchedulingPlaneResolver schedulingPlaneResolver;

    public DefaultWorkerDispatchResourcePolicy() {
        this(new DefaultSchedulingPlaneResolver());
    }

    public DefaultWorkerDispatchResourcePolicy(SchedulingPlaneResolver schedulingPlaneResolver) {
        this.schedulingPlaneResolver = Objects.requireNonNull(schedulingPlaneResolver, "schedulingPlaneResolver");
    }

    @Override
    public WorkerDispatchResourceUsage usageForTask(Task task) {
        return new WorkerDispatchResourceUsage(requiresExclusiveWorkerLock(task));
    }

    @Override
    public WorkerDispatchResourceUsage usageForAttempt(Task task) {
        return usageForTask(task);
    }

    private boolean requiresExclusiveWorkerLock(Task task) {
        return schedulingPlaneResolver.resolve(task)
                .taskSchedulingPolicy()
                .workerResourceMode() == WorkerResourceMode.EXCLUSIVE;
    }
}
