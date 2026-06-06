package com.xa.mass.engine.strategy;

import com.xa.mass.base.model.Task;
import com.xa.mass.engine.runtime.TaskRuntimeProfileResolver;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy;
import com.xa.mass.engine.runtime.scheduling.ResolvedWorkerSchedulingPolicy;
import com.xa.mass.engine.runtime.scheduling.SchedulingPlaneResolution;
import com.xa.mass.engine.runtime.scheduling.SchedulingPlaneResolver;
import com.xa.mass.engine.runtime.scheduling.TaskDispatchIntent;
import com.xa.mass.engine.runtime.scheduling.TaskPolicyPresetResolution;
import com.xa.mass.engine.runtime.scheduling.TaskPolicyPresetResolver;

import java.util.Objects;
import java.util.Set;

/**
 * Default behavior-neutral Scheduling Plane resolver.
 *
 * <p>This resolver mirrors current runtime profile and worker-route behavior.
 * It introduces no storage-backed catalog and no new scheduling behavior.</p>
 */
public final class DefaultSchedulingPlaneResolver implements SchedulingPlaneResolver {

    private final TaskPolicyPresetResolver taskPolicyPresetResolver;
    private final WorkerRoutingPolicy workerRoutingPolicy;

    public DefaultSchedulingPlaneResolver() {
        this(new TaskPolicyPresetResolver(), WorkerRoutingPolicy.defaultPolicy());
    }

    public DefaultSchedulingPlaneResolver(TaskRuntimeProfileResolver taskRuntimeProfileResolver,
                                          WorkerRoutingPolicy workerRoutingPolicy) {
        this(new TaskPolicyPresetResolver(taskRuntimeProfileResolver), workerRoutingPolicy);
    }

    public DefaultSchedulingPlaneResolver(TaskPolicyPresetResolver taskPolicyPresetResolver,
                                          WorkerRoutingPolicy workerRoutingPolicy) {
        this.taskPolicyPresetResolver = Objects.requireNonNull(taskPolicyPresetResolver,
                "taskPolicyPresetResolver");
        this.workerRoutingPolicy = workerRoutingPolicy == null ? WorkerRoutingPolicy.defaultPolicy() : workerRoutingPolicy;
    }

    @Override
    public SchedulingPlaneResolution resolve(Task task) {
        TaskDispatchIntent intent = TaskDispatchIntent.fromTask(task);
        TaskPolicyPresetResolution presetResolution = taskPolicyPresetResolver.resolve(task);
        Set<String> routeBucketKeys = workerRoutingPolicy.routeBucketKeysForTask(task);
        return new SchedulingPlaneResolution(
                intent,
                ResolvedTaskSchedulingPolicy.from(task, presetResolution),
                ResolvedWorkerSchedulingPolicy.from(intent, routeBucketKeys)
        );
    }
}
