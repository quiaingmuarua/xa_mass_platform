package com.xa.mass.engine.runtime;

import com.xa.mass.base.enums.task.TaskWorkloadClass;

/**
 * Engine-internal retry policy resolved once from the normalized task runtime
 * profile.
 *
 * <p>This keeps assignment retry and runtime retry visibility on one internal
 * mainline instead of scattering workload-aware retry semantics across
 * separate helper classes.
 */
public record TaskRuntimeRetryPolicy(
        TaskWorkloadClass workloadClass,
        long assignmentRetryDelayMillis,
        long workRetryDelayMillis
) {
}
