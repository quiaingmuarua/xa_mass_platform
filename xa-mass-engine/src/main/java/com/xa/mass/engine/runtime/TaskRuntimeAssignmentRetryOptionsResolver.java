package com.xa.mass.engine.runtime;

import com.xa.mass.base.model.Task;

/**
 * Resolves workload-aware assignment retry delay from the normalized runtime
 * profile.
 *
 * <p>This keeps no-match or refill retry behavior aligned with the task's
 * workload intent without forcing TaskAssignWorker back onto free-form task
 * config keys.</p>
 */
public class TaskRuntimeAssignmentRetryOptionsResolver {

    private final long interactiveRetryDelayMillis;
    private final TaskRuntimeProfileResolver profileResolver;

    public TaskRuntimeAssignmentRetryOptionsResolver() {
        this(
                Long.getLong("xa.mass.engine.interactiveAssignmentRetryDelayMillis", 100L),
                new TaskRuntimeProfileResolver()
        );
    }

    public TaskRuntimeAssignmentRetryOptionsResolver(long interactiveRetryDelayMillis,
                                                     TaskRuntimeProfileResolver profileResolver) {
        this.interactiveRetryDelayMillis = Math.max(1L, interactiveRetryDelayMillis);
        this.profileResolver = profileResolver;
    }

    public long resolve(Task task, long defaultRetryDelayMillis) {
        TaskRuntimeProfile profile = profileResolver.resolve(task);
        long normalizedDefaultDelayMillis = Math.max(1L, defaultRetryDelayMillis);
        return switch (profile.dispatchPriority()) {
            case HIGH -> Math.min(normalizedDefaultDelayMillis, interactiveRetryDelayMillis);
            case NORMAL -> normalizedDefaultDelayMillis;
        };
    }
}
