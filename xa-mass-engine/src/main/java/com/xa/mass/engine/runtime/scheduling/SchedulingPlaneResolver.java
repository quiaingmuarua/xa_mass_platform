package com.xa.mass.engine.runtime.scheduling;

import com.xa.mass.base.model.Task;

/**
 * Resolves task shell/config into engine-facing Scheduling Plane views.
 */
public interface SchedulingPlaneResolver {

    SchedulingPlaneResolution resolve(Task task);
}
