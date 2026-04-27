package com.xa.mass.engine.runtime;

import com.xa.mass.base.model.Task;
import com.xa.mass.engine.work.TaskWorkClaimOptions;

/**
 * Resolves workload-aware ready-claim options from the normalized runtime
 * profile plus the task's explicit batch setting.
 */
public class TaskRuntimeClaimOptionsResolver {

    static final int DEFAULT_INTERACTIVE_PER_WORKER_CAP = Integer.getInteger(
            "xa.mass.engine.interactivePerWorkerClaimLimit", 1);
    static final long DEFAULT_SHORT_LEASE_SECONDS = Long.getLong(
            "xa.mass.engine.interactiveLeaseSeconds", 30L);

    private final TaskRuntimeProfileResolver profileResolver;

    public TaskRuntimeClaimOptionsResolver() {
        this(new TaskRuntimeProfileResolver());
    }

    TaskRuntimeClaimOptionsResolver(TaskRuntimeProfileResolver profileResolver) {
        this.profileResolver = profileResolver;
    }

    public TaskWorkClaimOptions resolve(Task task, int workerCount, long defaultLeaseSeconds) {
        TaskRuntimeProfile profile = profileResolver.resolve(task);
        int taskBatchSize = task != null ? Math.max(task.getBatchSize(), 1) : 1;
        int perWorkerCapacity = switch (profile.batchPolicy()) {
            case SMALL -> Math.min(taskBatchSize, DEFAULT_INTERACTIVE_PER_WORKER_CAP);
            case LARGE -> taskBatchSize;
        };
        long leaseSeconds = switch (profile.leaseProfile()) {
            case SHORT -> Math.max(1L, Math.min(defaultLeaseSeconds, DEFAULT_SHORT_LEASE_SECONDS));
            case NORMAL -> Math.max(1L, defaultLeaseSeconds);
        };
        return TaskWorkClaimOptions.of(perWorkerCapacity, workerCount, leaseSeconds);
    }
}
