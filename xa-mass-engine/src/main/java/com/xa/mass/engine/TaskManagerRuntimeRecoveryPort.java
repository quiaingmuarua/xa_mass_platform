package com.xa.mass.engine;

import com.xa.mass.base.model.Task;

import java.util.List;

/**
 * Package-local adapter that keeps startup recovery and replay paths off the
 * full TaskManager facade.
 */
public final class TaskManagerRuntimeRecoveryPort implements TaskRuntimeRecoveryPort {

    private final TaskManager taskManager;

    public TaskManagerRuntimeRecoveryPort(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @Override
    public List<Task> getRuntimeDispatchableTasks(int limit) {
        return taskManager.getRuntimeDispatchableTasks(limit);
    }
}
