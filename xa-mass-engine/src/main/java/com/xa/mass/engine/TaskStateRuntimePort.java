package com.xa.mass.engine;

import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.TaskTerminalPolicyDecision;
import com.xa.mass.runtime.api.TaskWorkStats;

/**
 * Narrow task-state and terminal-convergence surface for state resolution and
 * bounded validation.
 */
interface TaskStateRuntimePort {

    Task getTask(String taskId);

    TaskWorkStats getTaskWorkStats(String taskId);

    TaskTerminalPolicyDecision evaluateTerminalPolicy(Task task, TaskWorkStats stats);
}

