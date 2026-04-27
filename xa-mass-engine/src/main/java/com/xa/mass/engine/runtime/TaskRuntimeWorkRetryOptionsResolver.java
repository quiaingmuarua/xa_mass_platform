package com.xa.mass.engine.runtime;

import com.xa.mass.base.model.Task;

/**
 * Resolves workload-aware runtime retry visibility delay.
 */
public class TaskRuntimeWorkRetryOptionsResolver {

    static final long DEFAULT_INTERACTIVE_WORK_RETRY_DELAY_MILLIS = Long.getLong(
            "xa.mass.engine.interactiveWorkRetryDelayMillis", 100L);
    static final long DEFAULT_BULK_WORK_RETRY_DELAY_MILLIS = Long.getLong(
            "xa.mass.engine.bulkWorkRetryDelayMillis", 0L);

    private final TaskRuntimeProfileResolver profileResolver;

    public TaskRuntimeWorkRetryOptionsResolver() {
        this(new TaskRuntimeProfileResolver());
    }

    TaskRuntimeWorkRetryOptionsResolver(TaskRuntimeProfileResolver profileResolver) {
        this.profileResolver = profileResolver;
    }

    public long resolveRetryDelayMillis(Task task) {
        TaskRuntimeProfile profile = profileResolver.resolve(task);
        return switch (profile.backpressureClass()) {
            case INTERACTIVE -> Math.max(0L, DEFAULT_INTERACTIVE_WORK_RETRY_DELAY_MILLIS);
            case BULK -> Math.max(0L, DEFAULT_BULK_WORK_RETRY_DELAY_MILLIS);
        };
    }
}
