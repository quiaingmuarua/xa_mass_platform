package com.xa.mass.engine.assignment;

import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.Task;

/**
 * Default per-task worker budget policy.
 *
 * <p>The policy is intentionally engine-internal. It gives workload classes a
 * conservative fixed ceiling without adding a public task-level override yet.</p>
 */
public final class DefaultWorkerBudgetPolicy implements WorkerBudgetPolicy {

    public static final int DEFAULT_INTERACTIVE_MAX_WORKERS = 5;
    public static final int DEFAULT_BULK_MAX_WORKERS = 20;

    @Override
    public WorkerBudgetDecision resolve(Task task, int desiredDispatchWorkerCount, int currentTaskWorkerCount) {
        int budget = budgetFor(task);
        int activeCount = Math.max(0, currentTaskWorkerCount);
        int available = Math.max(0, budget - activeCount);
        boolean limited = desiredDispatchWorkerCount > available;
        return new WorkerBudgetDecision(budget, activeCount, available, limited);
    }

    private int budgetFor(Task task) {
        TaskWorkloadClass workloadClass = task != null && task.getExecutionSpec() != null
                ? task.getExecutionSpec().getWorkloadClass()
                : null;
        if (workloadClass == TaskWorkloadClass.INTERACTIVE) {
            return DEFAULT_INTERACTIVE_MAX_WORKERS;
        }
        return DEFAULT_BULK_MAX_WORKERS;
    }
}
