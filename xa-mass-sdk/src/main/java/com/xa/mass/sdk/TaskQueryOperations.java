package com.xa.mass.sdk;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;

import java.util.List;

/**
 * Query/read surface for task inspection.
 *
 * <p>Task-message reads here are compatibility/demo diagnostics only. They are
 * not a commitment that future business detail retrieval will come directly
 * from engine-owned {@code TaskMsg} collections.</p>
 */
public interface TaskQueryOperations {

    Task getTask(String taskId);

    List<Task> getAllTasks();

    List<Task> getTasksByStatus(TaskStatus status);

    /**
     * Bounded compatibility/demo task-message snapshot. This is not pagination;
     * callers that need large-scale message detail should use trace/audit sinks.
     */
    List<TaskMsg> getTaskMessages(String taskId, int limit);

    TaskMsg getTaskMessage(String taskId, String messageId);

    List<TaskMsgAttempt> getTaskMessageAttempts(String taskId, String messageId);

    TaskMsgAttempt getLatestActiveTaskMessageAttempt(String taskId, String messageId);

    long countTaskMessages(String taskId);

    /**
     * Bounded runtime-state validation; does not deep-scan the full TaskMsg
     * projection.
     */
    Object validateTaskState(String taskId);

    /** Runtime-stats-driven task convergence probe; does not scan full TaskMsg snapshots. */
    Object resolveTaskState(String taskId);

    /**
     * Explicit compatibility-projection audit. This is diagnostic-only and may
     * require a bounded TaskMsg / TaskMsgAttempt snapshot.
     */
    Object auditTaskProjectionState(String taskId);
}
