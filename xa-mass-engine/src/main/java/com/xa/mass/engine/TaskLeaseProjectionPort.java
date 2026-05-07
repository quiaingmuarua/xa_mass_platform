package com.xa.mass.engine;

import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;

/**
 * Narrow compatibility-projection repair surface for runtime lease truth.
 */
public interface TaskLeaseProjectionPort {

    TaskMsgAttempt getLatestActiveTaskMessageAttempt(String taskId, String messageId);

    TaskMsgAttempt getLatestTaskMessageAttempt(String taskId, String messageId);

    void addTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt);

    void addTaskMessageProjection(String taskId, TaskMsg taskMsg);

    boolean updateTaskMessage(String taskId, TaskMsg taskMsg);
}
