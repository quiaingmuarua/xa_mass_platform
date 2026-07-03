package com.xa.mass.engine;

import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskStateValidationResult;

import java.util.Objects;

/**
 * Preferred engine bounded-read surface for cross-module task inspection.
 *
 * <p>This surface intentionally stops at task shell / aggregate state.
 * Compatibility message/attempt residue lives behind the explicit
 * compatibility query surface instead of the default engine query contract.
 */
public class TaskQueryService {

    private final TaskQueryPort taskQueries;

    public TaskQueryService(TaskQueryPort taskQueries) {
        this.taskQueries = Objects.requireNonNull(taskQueries, "taskQueries");
    }

    public Task getTask(String taskId) {
        return taskQueries.getTask(taskId);
    }

    public TaskStateResolutionResult resolveTaskState(String taskId) {
        return taskQueries.resolveTaskState(taskId);
    }

    public TaskStateValidationResult validateTaskState(String taskId) {
        return taskQueries.validateTaskState(taskId);
    }
}

