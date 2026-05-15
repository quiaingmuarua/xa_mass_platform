package com.xa.mass.engine.resource;

import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;

/**
 * Default resource usage semantics.
 *
 * <p>Foreground tasks keep the historical worker-level exclusive lock. Background
 * tasks rely on capacity reservation for stateless workers. A WorkerContext on a
 * candidate remains a legacy resource lifecycle payload until WorkerContext is
 * retired from the scheduling hot path.</p>
 */
public class DefaultWorkerDispatchResourcePolicy implements WorkerDispatchResourcePolicy {

    @Override
    public WorkerDispatchResourceUsage usageForTask(Task task) {
        return new WorkerDispatchResourceUsage(requiresExclusiveWorkerLock(task), false);
    }

    @Override
    public WorkerDispatchResourceUsage usageForCandidate(Task task, WorkerSchedulingCandidate candidate) {
        return new WorkerDispatchResourceUsage(
                requiresExclusiveWorkerLock(task),
                candidate != null && candidate.getWorkerContext() != null
        );
    }

    @Override
    public WorkerDispatchResourceUsage usageForAttempt(Task task, String workerContextId) {
        return new WorkerDispatchResourceUsage(
                requiresExclusiveWorkerLock(task),
                workerContextId != null && !workerContextId.isBlank()
        );
    }

    private boolean requiresExclusiveWorkerLock(Task task) {
        return task == null || task.getExecutionSpec() == null || task.getExecutionSpec().isForeground();
    }
}
