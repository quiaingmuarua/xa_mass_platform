package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskStateValidationResult;

import java.util.List;

/**
 * Narrow task-query surface for bounded shell/debug inspection.
 */
public interface TaskQueryPort {

    Task getTask(String taskId);

    List<Task> listTasksPaged(int offset, int limit);

    List<Task> getTasksByStatus(TaskStatus status);

    TaskStateResolutionResult resolveTaskState(String taskId);

    TaskStateValidationResult validateTaskState(String taskId);
}
