package com.xa.mass.engine.runtime;

import com.xa.mass.base.model.Task;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy;
import com.xa.mass.runtime.api.WorkEnqueueOptions;

/**
 * Resolves workload-aware ready-backlog limits for runtime enqueue.
 *
 * <p>Phase 2B keeps this intentionally small: the engine can distinguish
 * interactive and bulk tasks at enqueue time without introducing broader queue
 * redesign or storage-specific policy code.
 */
public class TaskRuntimeEnqueueOptionsResolver {

    private final int interactiveMaxReadyItemsPerTask;
    private final int bulkMaxReadyItemsPerTask;
    private final TaskRuntimeProfileResolver profileResolver;

    public TaskRuntimeEnqueueOptionsResolver() {
        this(
                Integer.getInteger("xa.mass.engine.interactiveMaxReadyItemsPerTask", 10_000),
                Integer.getInteger("xa.mass.engine.bulkMaxReadyItemsPerTask", WorkEnqueueOptions.UNLIMITED),
                new TaskRuntimeProfileResolver()
        );
    }

    TaskRuntimeEnqueueOptionsResolver(int interactiveMaxReadyItemsPerTask,
                                      int bulkMaxReadyItemsPerTask,
                                      TaskRuntimeProfileResolver profileResolver) {
        this.interactiveMaxReadyItemsPerTask = normalizeLimit(interactiveMaxReadyItemsPerTask);
        this.bulkMaxReadyItemsPerTask = normalizeLimit(bulkMaxReadyItemsPerTask);
        this.profileResolver = profileResolver;
    }

    WorkEnqueueOptions resolve(Task task) {
        TaskRuntimeProfile profile = profileResolver.resolve(task);
        int maxReadyItemsPerTask = switch (profile.backpressureClass()) {
            case INTERACTIVE -> interactiveMaxReadyItemsPerTask;
            case BULK -> bulkMaxReadyItemsPerTask;
        };
        return resolve(ResolvedTaskSchedulingPolicy.BackpressurePolicy.from(profile, maxReadyItemsPerTask));
    }

    public WorkEnqueueOptions resolve(ResolvedTaskSchedulingPolicy taskPolicy) {
        ResolvedTaskSchedulingPolicy.BackpressurePolicy backpressurePolicy = taskPolicy == null
                ? null
                : taskPolicy.backpressurePolicy();
        return resolve(backpressurePolicy);
    }

    public WorkEnqueueOptions resolve(ResolvedTaskSchedulingPolicy.BackpressurePolicy backpressurePolicy) {
        ResolvedTaskSchedulingPolicy.BackpressurePolicy resolvedPolicy = backpressurePolicy != null
                ? backpressurePolicy
                : new ResolvedTaskSchedulingPolicy.BackpressurePolicy(
                        TaskRuntimeProfile.BackpressureClass.BULK,
                        bulkMaxReadyItemsPerTask);
        return new WorkEnqueueOptions(resolvedPolicy.maxReadyItemsPerTask());
    }

    private static int normalizeLimit(int value) {
        return value <= 0 ? WorkEnqueueOptions.UNLIMITED : value;
    }
}
