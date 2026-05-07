package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.base.model.TaskMessageSnapshot;
import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskStateValidationResult;

import java.util.List;
import java.util.Objects;

/**
 * Preferred engine bounded-read surface for cross-module task inspection.
 *
 * <p>Read tiers exposed here are intentionally narrow:
 * task shell / aggregate reads are mainline, bounded {@link TaskMsg}
 * projection reads and {@link TaskMsgAttempt} audit reads remain
 * shell/debug compatibility helpers, and projection audit stays
 * diagnostic-only. This surface must not become a pagination/history or
 * runtime-correctness contract.
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

    public TaskMessageSnapshot getTaskMessageSnapshot(String taskId, int limit) {
        return taskManager != null ? taskManager.getTaskMessageSnapshot(taskId, limit) : taskQueries.getTaskMessageSnapshot(taskId, limit);
    }

    public TaskMsg getTaskMessageProjection(String taskId, String messageId) {
        return taskManager != null
                ? taskManager.getTaskMessageProjection(taskId, messageId)
                : taskQueries.getTaskMessageProjection(taskId, messageId);
    }

    public List<TaskMsgAttempt> getTaskMessageAttemptAuditTrail(String taskId, String messageId) {
        return taskManager != null
                ? taskManager.getTaskMessageAttemptAuditTrail(taskId, messageId)
                : taskQueries.getTaskMessageAttemptAuditTrail(taskId, messageId);
    }

    public TaskMsgAttempt getLatestTaskMessageAttemptAuditView(String taskId, String messageId) {
        return taskManager != null
                ? taskManager.getLatestTaskMessageAttemptAuditView(taskId, messageId)
                : taskQueries.getLatestTaskMessageAttemptAuditView(taskId, messageId);
    }

    public TaskMsgAttempt getLatestActiveTaskMessageAttempt(String taskId, String messageId) {
        return taskManager != null
                ? taskManager.getLatestActiveAttemptProjection(taskId, messageId)
                : taskQueries.getLatestActiveTaskMessageAttempt(taskId, messageId);
    }

    public TaskStateResolutionResult resolveTaskState(String taskId) {
        return taskManager != null ? taskManager.resolveTaskState(taskId) : taskQueries.resolveTaskState(taskId);
    }

    public TaskStateValidationResult validateTaskState(String taskId) {
        return taskManager != null ? taskManager.validateTaskState(taskId) : taskQueries.validateTaskState(taskId);
    }

}

