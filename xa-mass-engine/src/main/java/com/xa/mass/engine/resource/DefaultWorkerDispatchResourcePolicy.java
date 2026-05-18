package com.xa.mass.engine.resource;

import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;

/**
 * Default resource usage semantics.
 *
 * <p>Foreground tasks keep the worker-level exclusive lock. Background tasks
 * rely on capacity reservation. Resource policy is task/worker based.</p>
 */
public final class DefaultWorkerDispatchResourcePolicy implements WorkerDispatchResourcePolicy {

    @Override
    public WorkerDispatchResourceUsage usageForTask(Task task) {
        return new WorkerDispatchResourceUsage(requiresExclusiveWorkerLock(task));
    }

    @Override
    public WorkerDispatchResourceUsage usageForCandidate(Task task, WorkerSchedulingCandidate candidate) {
        return usageForTask(task);
    }

    @Override
    public WorkerDispatchResourceUsage usageForAttempt(Task task) {
        return usageForTask(task);
    }

    private boolean requiresExclusiveWorkerLock(Task task) {
        return task == null || task.getExecutionSpec() == null || task.getExecutionSpec().isForeground();
    }
}
