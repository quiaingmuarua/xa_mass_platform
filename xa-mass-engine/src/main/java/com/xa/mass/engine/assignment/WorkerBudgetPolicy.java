package com.xa.mass.engine.assignment;

import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy;

/**
 * Engine-internal owner for per-task worker budget decisions.
 */
public interface WorkerBudgetPolicy {

    WorkerBudgetDecision resolve(ResolvedTaskSchedulingPolicy taskPolicy,
                                 int desiredDispatchWorkerCount,
                                 int currentTaskWorkerCount);
}
