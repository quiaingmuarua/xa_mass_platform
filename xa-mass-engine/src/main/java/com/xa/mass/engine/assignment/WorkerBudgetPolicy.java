package com.xa.mass.engine.assignment;

import com.xa.mass.base.model.Task;

/**
 * Engine-internal owner for per-task worker budget decisions.
 */
public interface WorkerBudgetPolicy {

    WorkerBudgetDecision resolve(Task task, int desiredDispatchWorkerCount, int currentTaskWorkerCount);
}
