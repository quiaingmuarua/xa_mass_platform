package com.xa.mass.sdk;

import com.xa.mass.sdk.model.TaskAccessSnapshot;
import com.xa.mass.sdk.model.TaskDetailSnapshot;
import com.xa.mass.sdk.model.TaskStateSnapshot;
import com.xa.mass.sdk.model.TaskSummarySnapshot;

import java.util.List;

/** Query/read surface for task shell and aggregate inspection. */
public interface TaskQueryOperations {

    TaskDetailSnapshot getTaskDetail(String taskId);

    List<TaskSummarySnapshot> listTaskSummaries(int offset, int limit);

    List<TaskSummarySnapshot> getTaskSummariesByStatus(String status);

    boolean taskExists(String taskId);

    TaskStateSnapshot getTaskState(String taskId);

    TaskAccessSnapshot getTaskAccess(String taskId);
}
