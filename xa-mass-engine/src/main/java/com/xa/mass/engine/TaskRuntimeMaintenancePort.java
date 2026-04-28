package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.work.TaskWorkRuntime;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Narrow engine-internal port for runtime maintenance and watchdog paths.
 */
public interface TaskRuntimeMaintenancePort {

    TaskWorkRuntime getTaskWorkRuntime();

    boolean hasPendingDispatchableMessages(String taskId);

    boolean hasProcessingMessagesForWorker(String taskId, String workerId);

    void requestTaskDispatch(Task task);

    boolean expireTaskMessage(String taskId, String messageId);

    List<Task> pollExpiredMaxRuntimeTasks(LocalDateTime now, int limit);

    boolean terminateTask(String taskId, TaskTerminalReason reason);
}
