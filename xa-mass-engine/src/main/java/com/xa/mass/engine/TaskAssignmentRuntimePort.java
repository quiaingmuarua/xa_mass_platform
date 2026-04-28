package com.xa.mass.engine;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.engine.work.TaskWorkRuntime;

/**
 * Narrow engine-internal port for task assignment and message claim flows.
 */
public interface TaskAssignmentRuntimePort {

    int countPendingDispatchableMessages(String taskId);

    long getTaskMessageLeaseSeconds();

    TaskMsg getTaskMessage(String taskId, String messageId);

    boolean updateTaskMessage(String taskId, TaskMsg taskMsg);

    TaskMsgAttempt getLatestTaskMessageAttempt(String taskId, String messageId);

    void addTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt);

    boolean updateTask(Task task);

    TaskWorkRuntime getTaskWorkRuntime();
}
