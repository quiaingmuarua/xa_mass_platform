package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.work.ActiveLeaseRecord;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Package-local adapter that keeps watchdog and runtime-maintenance paths off
 * the full TaskManager facade.
 */
public final class TaskManagerRuntimeMaintenancePort implements TaskRuntimeMaintenancePort {

    private final TaskManager taskManager;

    public TaskManagerRuntimeMaintenancePort(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @Override
    public List<ActiveLeaseRecord> getActiveLeases(String taskId) {
        return taskManager.getTaskWorkRuntime().activeLeases(taskId);
    }

    @Override
    public List<ActiveLeaseRecord> pollExpiredLeases(int limit, Instant now) {
        return taskManager.getTaskWorkRuntime().pollExpiredLeases(limit, now);
    }

    @Override
    public boolean hasPendingDispatchableMessages(String taskId) {
        return taskManager.hasPendingDispatchableMessages(taskId);
    }

    @Override
    public boolean hasProcessingMessagesForWorker(String taskId, String workerId) {
        return taskManager.hasProcessingMessagesForWorker(taskId, workerId);
    }

    @Override
    public void requestTaskDispatch(Task task) {
        taskManager.requestTaskDispatch(task);
    }

    @Override
    public boolean expireTaskMessage(String taskId, String messageId) {
        return taskManager.expireTaskMessage(taskId, messageId);
    }

    @Override
    public List<Task> pollExpiredMaxRuntimeTasks(LocalDateTime now, int limit) {
        return taskManager.pollExpiredMaxRuntimeTasks(now, limit);
    }

    @Override
    public boolean terminateTask(String taskId, TaskTerminalReason reason) {
        return taskManager.terminateTask(taskId, reason);
    }
}
