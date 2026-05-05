package com.xa.mass.sdk;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;

import java.util.List;

/** Query/read surface for task inspection. */
public interface TaskQueryOperations {

    Task getTask(String taskId);

    List<Task> listTasksPaged(int offset, int limit);

    List<Task> getTasksByStatus(TaskStatus status);

    List<TaskMsg> getTaskMessages(String taskId, int limit);

    TaskMsg getTaskMessage(String taskId, String messageId);

    List<TaskMsgAttempt> getTaskMessageAttempts(String taskId, String messageId);

    TaskMsgAttempt getLatestActiveTaskMessageAttempt(String taskId, String messageId);

    long countTaskMessages(String taskId);

    Object validateTaskState(String taskId);

    Object resolveTaskState(String taskId);
}
