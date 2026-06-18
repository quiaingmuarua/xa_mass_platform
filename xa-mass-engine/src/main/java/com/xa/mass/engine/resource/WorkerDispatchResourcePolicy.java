package com.xa.mass.engine.resource;

import com.xa.mass.base.model.Task;

/**
 * Owns engine-internal resource usage semantics for a dispatch candidate.
 */
public interface WorkerDispatchResourcePolicy {

    WorkerDispatchResourceUsage usageForTask(Task task);

    WorkerDispatchResourceUsage usageForAttempt(Task task);
}
