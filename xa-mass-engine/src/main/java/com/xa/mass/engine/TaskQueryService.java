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
 * Preferred engine bounded-read surface for cross-module task inspection.
 *
 * <p>Read tiers exposed here are intentionally narrow:
 * task shell / aggregate reads are mainline, bounded {@link TaskMsg} /
 * {@link TaskMsgAttempt} reads remain shell/debug compatibility helpers, and
 * projection audit stays diagnostic-only.
 */
public class TaskQueryService {

    private final TaskManager taskManager;

    public TaskQueryService(TaskManager taskManager) {
        this.taskManager = Objects.requireNonNull(taskManager, "taskManager");
    }

    public Task getTask(String taskId) {
        return taskManager.getTask(taskId);
    }

    @Deprecated
    public List<Task> getAllTasks() {
        return taskManager.getAllTasks();
    }

    public List<Task> listTasksPaged(int offset, int limit) {
        return taskManager.listTasksPaged(offset, limit);
    }

    public List<Task> getTasksByStatus(TaskStatus status) {
        return taskManager.getTasksByStatus(status);
    }

    public List<TaskMsg> getTaskMessages(String taskId, int limit) {
        return taskManager.getTaskMessages(taskId, limit);
    }

    public long countTaskMessages(String taskId) {
        return taskManager.countTaskMessages(taskId);
    }

    public TaskMsg getTaskMessage(String taskId, String messageId) {
        return taskManager.getTaskMessage(taskId, messageId);
    }

    public List<TaskMsgAttempt> getTaskMessageAttempts(String taskId, String messageId) {
        return taskManager.getTaskMessageAttempts(taskId, messageId);
    }

    public TaskMsgAttempt getLatestTaskMessageAttempt(String taskId, String messageId) {
        return taskManager.getLatestTaskMessageAttempt(taskId, messageId);
    }

    public TaskMsgAttempt getLatestActiveTaskMessageAttempt(String taskId, String messageId) {
        return taskManager.getLatestActiveTaskMessageAttempt(taskId, messageId);
    }

    public TaskStateResolutionResult resolveTaskState(String taskId) {
        return taskManager.resolveTaskState(taskId);
    }

    public TaskStateValidationResult validateTaskState(String taskId) {
        return taskManager.validateTaskState(taskId);
    }

    public TaskStateValidationResult auditTaskProjectionState(String taskId) {
        return taskManager.auditTaskProjectionState(taskId);
    }
}
