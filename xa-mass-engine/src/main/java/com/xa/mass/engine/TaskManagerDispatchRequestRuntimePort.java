package com.xa.mass.engine;

import com.xa.mass.base.model.Task;

/**
 * Package-local adapter that keeps dispatch request orchestration off the full
 * TaskManager facade.
 */
final class TaskManagerDispatchRequestRuntimePort implements TaskDispatchRequestRuntimePort {

    private final TaskManager taskManager;

    TaskManagerDispatchRequestRuntimePort(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @Override
    public Task getTask(String taskId) {
        return taskManager.getTask(taskId);
    }

    @Override
    public boolean hasPendingDispatchableMessages(String taskId) {
        return taskManager.hasPendingDispatchableMessages(taskId);
    }

    @Override
    public void publishTaskDispatchRequested(Task task) {
        taskManager.publishTaskDispatchRequested(task);
    }
}
