package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskStateValidationResult;

import java.util.List;
import java.util.Objects;

/**
 * Package-local adapter that keeps bounded task queries off the full
 * {@link TaskManager} facade.
 */
final class TaskManagerQueryPort implements TaskQueryPort {

    private final TaskManager taskManager;

    TaskManagerQueryPort(TaskManager taskManager) {
        this.taskManager = Objects.requireNonNull(taskManager, "taskManager");
    }

    @Override
    public Task getTask(String taskId) {
        return taskManager.getTask(taskId);
    }

    @Override
    public List<Task> listTasksPaged(int offset, int limit) {
        return taskManager.listTasksPaged(offset, limit);
    }

    @Override
    public List<Task> getTasksByStatus(TaskStatus status) {
        return taskManager.getTasksByStatus(status);
    }

    @Override
    public List<TaskMsg> getTaskMessages(String taskId, int limit) {
        return taskManager.getTaskMessages(taskId, limit);
    }

    @Override
    public long countTaskMessages(String taskId) {
        return taskManager.countTaskMessages(taskId);
    }

    @Override
    public TaskMsg getTaskMessage(String taskId, String messageId) {
        return taskManager.getTaskMessage(taskId, messageId);
    }

    @Override
    public List<TaskMsgAttempt> getTaskMessageAttempts(String taskId, String messageId) {
        return taskManager.getTaskMessageAttempts(taskId, messageId);
    }

    @Override
    public TaskMsgAttempt getLatestTaskMessageAttempt(String taskId, String messageId) {
        return taskManager.getLatestTaskMessageAttempt(taskId, messageId);
    }

    @Override
    public TaskMsgAttempt getLatestActiveTaskMessageAttempt(String taskId, String messageId) {
        return taskManager.getLatestActiveTaskMessageAttempt(taskId, messageId);
    }

    @Override
    public TaskStateResolutionResult resolveTaskState(String taskId) {
        return taskManager.resolveTaskState(taskId);
    }

    @Override
    public TaskStateValidationResult validateTaskState(String taskId) {
        return taskManager.validateTaskState(taskId);
    }
}
