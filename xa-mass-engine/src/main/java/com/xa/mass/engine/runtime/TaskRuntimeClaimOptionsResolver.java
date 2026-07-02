package com.xa.mass.engine.runtime;

import com.xa.mass.base.model.Task;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy;
import com.xa.mass.task.runtime.ClaimLeasePolicy;

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

    ClaimLeasePolicy resolve(Task task, int workerCount, long defaultLeaseSeconds) {
        TaskRuntimeProfile profile = profileResolver.resolve(task);
        return resolve(task,
                ResolvedTaskSchedulingPolicy.ClaimPolicy.from(
                        profile,
                DEFAULT_INTERACTIVE_PER_WORKER_CAP,
                DEFAULT_SHORT_LEASE_SECONDS),
                workerCount,
                defaultLeaseSeconds);
    }

    public ClaimLeasePolicy resolve(Task task,
                                    ResolvedTaskSchedulingPolicy taskPolicy,
                                    int workerCount,
                                    long defaultLeaseSeconds) {
        ResolvedTaskSchedulingPolicy.ClaimPolicy claimPolicy = taskPolicy == null
                ? null
                : taskPolicy.claimPolicy();
        return resolve(task, claimPolicy, workerCount, defaultLeaseSeconds);
    }

    public ClaimLeasePolicy resolve(Task task,
                                    ResolvedTaskSchedulingPolicy.ClaimPolicy claimPolicy,
                                    int workerCount,
                                    long defaultLeaseSeconds) {
        ResolvedTaskSchedulingPolicy.ClaimPolicy resolvedPolicy = claimPolicy != null
                ? claimPolicy
                : ResolvedTaskSchedulingPolicy.ClaimPolicy.from(
                        profileResolver.resolve(task),
                        DEFAULT_INTERACTIVE_PER_WORKER_CAP,
                        DEFAULT_SHORT_LEASE_SECONDS);
        int taskBatchSize = task != null ? Math.max(task.getExecutionSpec().getBatchSize(), 1) : 1;
        int perWorkerCapacity = switch (resolvedPolicy.batchPolicy()) {
            case SMALL -> Math.min(taskBatchSize, resolvedPolicy.smallPerWorkerCapacityLimit());
            case LARGE -> taskBatchSize;
        };
        long leaseSeconds = switch (resolvedPolicy.leaseProfile()) {
            case SHORT -> Math.max(1L, Math.min(defaultLeaseSeconds, resolvedPolicy.shortLeaseSeconds()));
            case NORMAL -> Math.max(1L, defaultLeaseSeconds);
        };
        return new ClaimLeasePolicy(
                perWorkerCapacity * Math.max(1, workerCount),
                leaseSeconds * 1_000L);
    }
}
