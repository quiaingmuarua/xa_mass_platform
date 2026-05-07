package com.xa.mass.engine;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
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

    /**
     * @deprecated compatibility residue read only; avoid introducing new
     * callers that treat compatibility snapshots as engine truth.
     */
    @Deprecated
    @CompatibilityProjectionOnly
    public TaskMessageSnapshotView getTaskMessageSnapshotView(String taskId, int limit) {
        return taskManager != null
                ? taskManager.getTaskMessageSnapshotView(taskId, limit)
                : taskQueries.getTaskMessageSnapshotView(taskId, limit);
    }

    /**
     * @deprecated compatibility residue read only.
     */
    @Deprecated
    @CompatibilityProjectionOnly
    public TaskMessageView getTaskMessageView(String taskId, String messageId) {
        return taskManager != null
                ? taskManager.getTaskMessageView(taskId, messageId)
                : taskQueries.getTaskMessageView(taskId, messageId);
    }

    /**
     * @deprecated compatibility audit read only.
     */
    @Deprecated
    @CompatibilityProjectionOnly
    public List<TaskMessageAttemptView> getTaskMessageAttemptAuditViews(String taskId, String messageId) {
        return taskManager != null
                ? taskManager.getTaskMessageAttemptAuditViews(taskId, messageId)
                : taskQueries.getTaskMessageAttemptAuditViews(taskId, messageId);
    }

    /**
     * @deprecated compatibility audit read only.
     */
    @Deprecated
    @CompatibilityProjectionOnly
    public TaskMessageAttemptView getLatestTaskMessageAttemptView(String taskId, String messageId) {
        return taskManager != null
                ? taskManager.getLatestTaskMessageAttemptView(taskId, messageId)
                : taskQueries.getLatestTaskMessageAttemptView(taskId, messageId);
    }

    /**
     * @deprecated transitional compatibility lookup only; runtime lease state
     * remains the active-attempt truth.
     */
    @Deprecated
    @CompatibilityProjectionOnly
    public TaskMessageAttemptView getLatestActiveTaskMessageAttemptView(String taskId, String messageId) {
        return taskManager != null
                ? taskManager.getLatestActiveTaskMessageAttemptView(taskId, messageId)
                : taskQueries.getLatestActiveTaskMessageAttemptView(taskId, messageId);
    }

    public TaskStateResolutionResult resolveTaskState(String taskId) {
        return taskManager != null ? taskManager.resolveTaskState(taskId) : taskQueries.resolveTaskState(taskId);
    }

    public TaskStateValidationResult validateTaskState(String taskId) {
        return taskManager != null ? taskManager.validateTaskState(taskId) : taskQueries.validateTaskState(taskId);
    }

}

