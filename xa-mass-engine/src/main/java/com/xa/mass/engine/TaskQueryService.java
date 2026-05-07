package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskStateValidationResult;

import java.util.List;
import java.util.Objects;

/**
 * Preferred engine bounded-read surface for cross-module task inspection.
 *
 * <p>This surface intentionally stops at task shell / aggregate state. TaskMsg
 * and TaskMsgAttempt compatibility residue must not leak back out as an engine
 * query contract.
 */
public class TaskQueryService {

    private final TaskManager taskManager;
    private final TaskQueryPort taskQueries;

    public TaskQueryService(TaskManager taskManager) {
        this.taskManager = Objects.requireNonNull(taskManager, "taskManager");
        this.taskQueries = null;
    }

    public TaskQueryService(TaskQueryPort taskQueries) {
        this.taskManager = null;
        this.taskQueries = Objects.requireNonNull(taskQueries, "taskQueries");
    }

    public Task getTask(String taskId) {
        return taskManager != null ? taskManager.getTask(taskId) : taskQueries.getTask(taskId);
    }

    public List<Task> listTasksPaged(int offset, int limit) {
        return taskManager != null ? taskManager.listTasksPaged(offset, limit) : taskQueries.listTasksPaged(offset, limit);
    }

    public List<Task> getTasksByStatus(TaskStatus status) {
        return taskManager != null ? taskManager.getTasksByStatus(status) : taskQueries.getTasksByStatus(status);
    }

    public TaskStateResolutionResult resolveTaskState(String taskId) {
        return taskManager != null ? taskManager.resolveTaskState(taskId) : taskQueries.resolveTaskState(taskId);
    }

    public TaskStateValidationResult validateTaskState(String taskId) {
        return taskManager != null ? taskManager.validateTaskState(taskId) : taskQueries.validateTaskState(taskId);
    }

}

