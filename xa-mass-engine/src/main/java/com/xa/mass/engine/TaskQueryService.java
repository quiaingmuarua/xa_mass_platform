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
 * <p>This surface intentionally stops at task shell / aggregate state.
 * TaskMsg and TaskMsgAttempt residue lives behind the explicit compatibility
 * query surface instead of the default engine query contract.
 */
public class TaskQueryService {

    private final TaskQueryPort taskQueries;

    public TaskQueryService(TaskQueryPort taskQueries) {
        this.taskQueries = Objects.requireNonNull(taskQueries, "taskQueries");
    }

    public Task getTask(String taskId) {
        return taskQueries.getTask(taskId);
    }

    public List<Task> listTasksPaged(int offset, int limit) {
        return taskQueries.listTasksPaged(offset, limit);
    }

    public List<Task> getTasksByStatus(TaskStatus status) {
        return taskQueries.getTasksByStatus(status);
    }

    public TaskStateResolutionResult resolveTaskState(String taskId) {
        return taskQueries.resolveTaskState(taskId);
    }

    public TaskStateValidationResult validateTaskState(String taskId) {
        return taskQueries.validateTaskState(taskId);
    }

    @CompatibilityProjectionOnly
    public TaskStateValidationResult auditTaskProjectionState(String taskId) {
        return taskQueries.auditTaskProjectionState(taskId);
    }
}

