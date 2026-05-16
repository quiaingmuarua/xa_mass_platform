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
        boolean legacyWorkerContextResource = candidate != null
                && candidate.getSchedulingView() != null
                && candidate.getSchedulingView().hasWorkerContext();
        return new WorkerDispatchResourceUsage(
                requiresExclusiveWorkerLock(task) || legacyWorkerContextResource,
                legacyWorkerContextResource
        );
    }

    @Override
    public WorkerDispatchResourceUsage usageForAttempt(Task task, String workerContextId) {
        boolean legacyWorkerContextResource = workerContextId != null && !workerContextId.isBlank();
        return new WorkerDispatchResourceUsage(
                requiresExclusiveWorkerLock(task) || legacyWorkerContextResource,
                legacyWorkerContextResource
        );
    }

    private boolean requiresExclusiveWorkerLock(Task task) {
        return task == null || task.getExecutionSpec() == null || task.getExecutionSpec().isForeground();
    }
}
