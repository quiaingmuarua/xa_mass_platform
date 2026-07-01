package com.xa.mass.engine;

import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.TaskTerminalPolicyDecision;
import com.xa.mass.task.runtime.TaskRuntimeProgressSnapshot;

/**
 * Narrow task-state and terminal-convergence surface for state resolution and
 * bounded validation.
 */
interface TaskStateRuntimePort {

    Task getTask(String taskId);

    TaskRuntimeProgressSnapshot getTaskRuntimeProgressSnapshot(String taskId);

    TaskTerminalPolicyDecision evaluateTerminalPolicy(Task task, TaskRuntimeProgressSnapshot stats);
}

