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
 * Engine read surface for bounded task inspection and compatibility projection
 * access. This keeps shell/debug query flows off the runtime mutation facade.
 */
public class TaskQueryService {

    private final TaskManager taskManager;

    public TaskQueryService(TaskManager taskManager) {
        this.taskManager = Objects.requireNonNull(taskManager, "taskManager");
    }

    public Task getTask(String taskId) {
        return taskManager.getTask(taskId);
    }

    public List<Task> getAllTasks() {
        return taskManager.getAllTasks();
    }

    public List<Task> getTasksByStatus(TaskStatus status) {
        return taskManager.getTasksByStatus(status);
    }

    public List<TaskMsg> getTaskMessages(String taskId, int limit) {
        return taskManager.getTaskStorage().getTaskMessages(taskId, limit);
    }

    public long countTaskMessages(String taskId) {
        return taskManager.getTaskStorage().countTaskMessages(taskId);
    }

    public TaskMsg getTaskMessage(String taskId, String messageId) {
        return taskManager.getTaskStorage().getTaskMessage(taskId, messageId).orElse(null);
    }

    public List<TaskMsgAttempt> getTaskMessageAttempts(String taskId, String messageId) {
        return taskManager.getTaskStorage().getTaskMessageAttempts(taskId, messageId);
    }

    public TaskMsgAttempt getLatestActiveTaskMessageAttempt(String taskId, String messageId) {
        return taskManager.getTaskStorage().getLatestActiveTaskMessageAttempt(taskId, messageId).orElse(null);
    }

    public TaskStateResolutionResult resolveTaskState(String taskId) {
        return taskManager.resolveTaskState(taskId);
    }

    public TaskStateValidationResult validateTaskState(String taskId) {
        return taskManager.validateTaskState(taskId);
    }
}
