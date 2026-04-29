package com.xa.mass.engine;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.engine.model.TaskTerminalPolicyDecision;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.TaskWorkStats;

import java.util.List;

/**
 * Narrow engine-internal runtime surface for task lifecycle transitions and
 * terminal cleanup.
 */
interface TaskLifecycleRuntimePort extends TaskLeaseProjectionPort {

    Task getTask(String taskId);

    boolean updateTask(Task task);

    TaskWorkStats getTaskWorkStats(String taskId);

    TaskTerminalPolicyDecision evaluateTerminalPolicy(Task task, TaskWorkStats stats);

    void publishTaskReady(Task task);

    void publishTaskTerminal(Task task);

    void publishTaskMessageAttemptClosed(Task task, TaskMsg taskMsg, TaskMsgAttempt attempt);

    void publishTaskMessageLogicallyFinal(Task task, TaskMsg taskMsg);

    void pauseTaskScheduling(String taskId);

    void resumeTaskScheduling(String taskId);

    void cancelTaskScheduling(String taskId);

    void addTaskMessage(String taskId, TaskMsg taskMsg);

    void requestTaskDispatch(Task task);

    boolean deleteTaskRecord(String taskId);

    void discardTaskRuntime(String taskId);

    List<TaskMsg> getNonFinalTaskMessages(String taskId);

    List<ActiveLeaseRecord> getActiveLeases(String taskId);

    boolean updateTaskMessage(String taskId, TaskMsg taskMsg);

    boolean updateTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt);
}

