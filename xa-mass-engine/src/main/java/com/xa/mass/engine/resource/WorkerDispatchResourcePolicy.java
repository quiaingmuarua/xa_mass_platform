package com.xa.mass.engine.resource;

import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;

/**
 * Owns engine-internal resource usage semantics for a dispatch candidate.
 */
public interface WorkerDispatchResourcePolicy {

    WorkerDispatchResourceUsage usageForTask(Task task);

    WorkerDispatchResourceUsage usageForCandidate(Task task, WorkerSchedulingCandidate candidate);

    WorkerDispatchResourceUsage usageForAttempt(Task task);
}
