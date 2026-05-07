package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.base.model.TaskMessageSnapshot;
import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskStateValidationResult;

import java.util.List;

/**
 * Narrow task-query surface for bounded shell/debug inspection.
 */
public interface TaskQueryPort {

    Task getTask(String taskId);

    List<Task> listTasksPaged(int offset, int limit);

    List<Task> getTasksByStatus(TaskStatus status);

    TaskMessageSnapshot getTaskMessageSnapshot(String taskId, int limit);

    TaskMsg getTaskMessageProjection(String taskId, String messageId);

    List<TaskMsgAttempt> getTaskMessageAttemptAuditTrail(String taskId, String messageId);

    TaskMsgAttempt getLatestTaskMessageAttemptAuditView(String taskId, String messageId);

    TaskMsgAttempt getLatestActiveTaskMessageAttempt(String taskId, String messageId);

    TaskStateResolutionResult resolveTaskState(String taskId);

    TaskStateValidationResult validateTaskState(String taskId);
}
