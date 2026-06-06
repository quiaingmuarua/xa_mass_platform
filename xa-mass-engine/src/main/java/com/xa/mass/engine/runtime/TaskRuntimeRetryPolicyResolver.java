package com.xa.mass.engine.runtime;

import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy;

/**
 * Resolves workload-aware retry policy from the normalized runtime profile.
 *
 * <p>Current phase keeps the scope intentionally narrow: assignment requeue
 * delay and runtime retry visibility delay resolve together so engine retry
 * behavior has one internal source of truth.
 */
public class TaskRuntimeRetryPolicyResolver {

    private final long interactiveAssignmentRetryDelayMillis;
    private final long interactiveWorkRetryDelayMillis;
    private final long bulkWorkRetryDelayMillis;
    private final TaskRuntimeProfileResolver profileResolver;

    public TaskRuntimeRetryPolicyResolver() {
        this(
                longProperty("xa.mass.engine.interactiveAssignmentRetryDelayMillis", 100L),
                longProperty("xa.mass.engine.interactiveWorkRetryDelayMillis", 100L),
                longProperty("xa.mass.engine.bulkWorkRetryDelayMillis", 0L),
                new TaskRuntimeProfileResolver()
        );
    }

    public TaskRuntimeRetryPolicyResolver(long interactiveAssignmentRetryDelayMillis,
                                          long interactiveWorkRetryDelayMillis,
                                          long bulkWorkRetryDelayMillis,
                                          TaskRuntimeProfileResolver profileResolver) {
        this.interactiveAssignmentRetryDelayMillis = Math.max(1L, interactiveAssignmentRetryDelayMillis);
        this.interactiveWorkRetryDelayMillis = Math.max(0L, interactiveWorkRetryDelayMillis);
        this.bulkWorkRetryDelayMillis = Math.max(0L, bulkWorkRetryDelayMillis);
        this.profileResolver = profileResolver;
    }

    TaskRuntimeRetryPolicy resolve(Task task, long defaultAssignmentRetryDelayMillis) {
        TaskRuntimeProfile profile = profileResolver.resolve(task);
        return resolve(
                ResolvedTaskSchedulingPolicy.RetryPolicy.from(
                        profile,
                        interactiveAssignmentRetryDelayMillis,
                        interactiveWorkRetryDelayMillis,
                        bulkWorkRetryDelayMillis),
                defaultAssignmentRetryDelayMillis);
    }

    public TaskRuntimeRetryPolicy resolve(ResolvedTaskSchedulingPolicy taskPolicy,
                                          long defaultAssignmentRetryDelayMillis) {
        ResolvedTaskSchedulingPolicy.RetryPolicy retryPolicy = taskPolicy == null ? null : taskPolicy.retryPolicy();
        return resolve(retryPolicy, defaultAssignmentRetryDelayMillis);
    }

    public TaskRuntimeRetryPolicy resolve(ResolvedTaskSchedulingPolicy.RetryPolicy retryPolicy,
                                          long defaultAssignmentRetryDelayMillis) {
        ResolvedTaskSchedulingPolicy.RetryPolicy resolvedPolicy = retryPolicy != null
                ? retryPolicy
                : ResolvedTaskSchedulingPolicy.RetryPolicy.from(
                        new TaskRuntimeProfile(
                                TaskWorkloadClass.BULK,
                                TaskRuntimeProfile.DispatchLane.BULK,
                                TaskRuntimeProfile.DispatchPriority.NORMAL,
                                TaskRuntimeProfile.BatchPolicy.LARGE,
                                TaskRuntimeProfile.LeaseProfile.NORMAL,
                                TaskRuntimeProfile.BackpressureClass.BULK
                        ),
                        interactiveAssignmentRetryDelayMillis,
                        interactiveWorkRetryDelayMillis,
                        bulkWorkRetryDelayMillis);
        long normalizedAssignmentDefaultDelayMillis = Math.max(1L, defaultAssignmentRetryDelayMillis);
        return switch (resolvedPolicy.workloadClass()) {
            case INTERACTIVE -> new TaskRuntimeRetryPolicy(
                    resolvedPolicy.workloadClass(),
                    Math.min(normalizedAssignmentDefaultDelayMillis,
                            resolvedPolicy.interactiveAssignmentRetryDelayMillis()),
                    resolvedPolicy.interactiveWorkRetryDelayMillis()
            );
            case BULK -> new TaskRuntimeRetryPolicy(
                    resolvedPolicy.workloadClass(),
                    normalizedAssignmentDefaultDelayMillis,
                    resolvedPolicy.bulkWorkRetryDelayMillis()
            );
        };
    }

    private static long longProperty(String key, long defaultValue) {
        return Long.getLong(key, defaultValue);
    }
}
