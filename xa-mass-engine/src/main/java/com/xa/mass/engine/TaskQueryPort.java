package com.xa.mass.engine;

import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskStateValidationResult;

/**
 * Narrow task-query surface for bounded shell/debug inspection.
 */
public interface TaskQueryPort {

    Task getTask(String taskId);

    TaskStateResolutionResult resolveTaskState(String taskId);

    TaskStateValidationResult validateTaskState(String taskId);
}
