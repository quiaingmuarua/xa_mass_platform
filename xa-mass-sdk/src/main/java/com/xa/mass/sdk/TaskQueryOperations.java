package com.xa.mass.sdk;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.base.model.TaskMessageSnapshot;

import java.util.List;

/** Query/read surface for bounded task inspection plus explicit compatibility residue reads. */
public interface TaskQueryOperations {

    Task getTask(String taskId);

    List<Task> listTasksPaged(int offset, int limit);

    List<Task> getTasksByStatus(TaskStatus status);

    TaskMessageSnapshot getTaskMessageSnapshot(String taskId, int limit);

    TaskMsg getTaskMessageProjection(String taskId, String messageId);

    List<TaskMsgAttempt> getTaskMessageAttemptAuditTrail(String taskId, String messageId);

    TaskMsgAttempt getLatestActiveTaskMessageAttempt(String taskId, String messageId);

    Object validateTaskState(String taskId);

    Object resolveTaskState(String taskId);
}
