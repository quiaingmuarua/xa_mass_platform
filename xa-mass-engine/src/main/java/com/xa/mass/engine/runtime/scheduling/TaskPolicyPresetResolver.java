package com.xa.mass.engine.runtime.scheduling;

import com.xa.mass.base.model.Task;
import com.xa.mass.engine.runtime.TaskRuntimeProfile;
import com.xa.mass.engine.runtime.TaskRuntimeProfileResolver;

import java.util.Objects;

/**
 * Resolves legacy task preset fields into explicit task scheduling values.
 */
public final class TaskPolicyPresetResolver {

    private final TaskRuntimeProfileResolver runtimeProfileResolver;

    public TaskPolicyPresetResolver() {
        this(new TaskRuntimeProfileResolver());
    }

    public TaskPolicyPresetResolver(TaskRuntimeProfileResolver runtimeProfileResolver) {
        this.runtimeProfileResolver = Objects.requireNonNull(runtimeProfileResolver, "runtimeProfileResolver");
    }

    public TaskPolicyPresetResolution resolve(Task task) {
        TaskRuntimeProfile profile = runtimeProfileResolver.resolve(task);
        return TaskPolicyPresetResolution.from(task, profile);
    }
}
