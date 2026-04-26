package com.xa.mass.sdk;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;

import java.util.List;

/**
 * Query/read surface for task inspection.
 */
public interface TaskQueryOperations {

    Task getTask(String taskId);

    List<Task> getAllTasks();

    List<Task> getTasksByStatus(TaskStatus status);

    List<TaskMsg> getTaskMessages(String taskId);

    TaskMsg getTaskMessage(String taskId, String messageId);

    List<TaskMsgAttempt> getTaskMessageAttempts(String taskId, String messageId);

    TaskMsgAttempt getLatestActiveTaskMessageAttempt(String taskId, String messageId);

    List<TaskMsg> getTaskMessagesPage(String taskId, int offset, int limit);

    long countTaskMessages(String taskId);

    Object validateTaskState(String taskId);

    Object resolveTaskStateFromMessages(String taskId);
}
