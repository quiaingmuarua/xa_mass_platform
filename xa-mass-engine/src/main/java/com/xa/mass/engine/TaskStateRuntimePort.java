package com.xa.mass.engine;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.engine.model.TaskTerminalPolicyDecision;
import com.xa.mass.storage.api.TaskStorage;
import com.xa.mass.runtime.api.TaskWorkStats;

import java.util.List;

/**
 * Narrow task-state and terminal-convergence surface for state resolution and
 * bounded validation.
 */
public interface TaskStateRuntimePort {

    Task getTask(String taskId);

    boolean updateTask(Task task);

    TaskWorkStats getTaskWorkStats(String taskId);

    TaskTerminalPolicyDecision evaluateTerminalPolicy(Task task, TaskWorkStats stats);

    void publishTaskTerminal(Task task);

    /**
     * Diagnostic-only full projection read for explicit audit flows.
     */
    List<TaskMsg> getTaskMessagesForProjectionAudit(String taskId);

    TaskStorage.TaskMessageAttemptStats getTaskMessageAttemptStats(String taskId, String messageId);
}

