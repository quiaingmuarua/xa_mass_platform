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

/**
 * Default behavior-neutral Scheduling Plane resolver.
 *
 * <p>This resolver mirrors current runtime profile and candidate-bucket behavior.
 * It introduces no storage-backed catalog and no new scheduling behavior.</p>
 */
public final class DefaultSchedulingPlaneResolver implements SchedulingPlaneResolver {

    private final TaskPolicyPresetResolver taskPolicyPresetResolver;

    public DefaultSchedulingPlaneResolver() {
        this(new TaskPolicyPresetResolver());
    }

    public DefaultSchedulingPlaneResolver(TaskRuntimeProfileResolver taskRuntimeProfileResolver) {
        this(new TaskPolicyPresetResolver(taskRuntimeProfileResolver));
    }

    public DefaultSchedulingPlaneResolver(TaskPolicyPresetResolver taskPolicyPresetResolver) {
        this.taskPolicyPresetResolver = Objects.requireNonNull(taskPolicyPresetResolver,
                "taskPolicyPresetResolver");
    }

    @Override
    public SchedulingPlaneResolution resolve(Task task) {
        TaskDispatchIntent intent = TaskDispatchIntent.fromTask(task);
        TaskPolicyPresetResolution presetResolution = taskPolicyPresetResolver.resolve(task);
        return new SchedulingPlaneResolution(
                intent,
                ResolvedTaskSchedulingPolicy.from(task, presetResolution),
                ResolvedWorkerSchedulingPolicy.from(intent)
        );
    }
}
