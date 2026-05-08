package com.xa.mass.sdk;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;

import java.util.List;

/** Query/read surface for task shell and aggregate inspection. */
public interface TaskQueryOperations {

    Task getTask(String taskId);

    List<Task> listTasksPaged(int offset, int limit);

    List<Task> getTasksByStatus(TaskStatus status);
}
