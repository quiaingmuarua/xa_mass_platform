package com.xa.mass.engine;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.engine.model.TaskTerminalPolicyDecision;
import com.xa.mass.engine.storage.TaskStorage;
import com.xa.mass.engine.work.TaskWorkStats;

import java.util.List;

/**
 * Package-local task-state adapter that keeps convergence and validation logic
 * off the TaskManager facade.
 */
final class TaskManagerStateRuntimePort implements TaskStateRuntimePort {

    private final TaskManager taskManager;

    TaskManagerStateRuntimePort(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @Override
    public Task getTask(String taskId) {
        return taskManager.getTask(taskId);
    }

    @Override
    public boolean updateTask(Task task) {
        return taskManager.updateTask(task);
    }

    @Override
    public TaskWorkStats getTaskWorkStats(String taskId) {
        return taskManager.getTaskWorkStats(taskId);
    }

    @Override
    public TaskTerminalPolicyDecision evaluateTerminalPolicy(Task task, TaskWorkStats stats) {
        return taskManager.evaluateTerminalPolicy(task, stats);
    }

    @Override
    public void publishTaskTerminal(Task task) {
        taskManager.publishTaskTerminal(task);
    }

    @Override
    public List<TaskMsg> getTaskMessagesForProjectionAudit(String taskId) {
        return taskManager.getTaskStorage().getTaskMessages(taskId);
    }

    @Override
    public TaskStorage.TaskMessageAttemptStats getTaskMessageAttemptStats(String taskId, String messageId) {
        return taskManager.getTaskMessageAttemptStats(taskId, messageId);
    }
}
