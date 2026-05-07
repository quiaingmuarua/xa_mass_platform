package com.xa.mass.sdk;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;

import java.util.List;

/** Query/read surface for bounded task inspection plus explicit compatibility residue views. */
public interface TaskQueryOperations {

    Task getTask(String taskId);

    List<Task> listTasksPaged(int offset, int limit);

    List<Task> getTasksByStatus(TaskStatus status);

    SdkTaskMessageSnapshot getTaskMessageSnapshot(String taskId, int limit);

    SdkTaskMessageView getTaskMessageView(String taskId, String messageId);

    List<SdkTaskMessageAttemptView> getTaskMessageAttemptViews(String taskId, String messageId);

    SdkTaskMessageAttemptView getLatestActiveTaskMessageAttemptView(String taskId, String messageId);

    Object validateTaskState(String taskId);

    Object resolveTaskState(String taskId);
}
