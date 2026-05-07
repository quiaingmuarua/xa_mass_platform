package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskCreateRequestDto;
import com.xa.mass.engine.model.TaskResumeResult;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Preferred engine write/control surface for cross-module task lifecycle and
 * task-owned intake mutations.
 *
 * <p>Shell, SDK, transport, and testing flows should use this surface instead
 * of carrying the broader {@link TaskManager} orchestration facade.
 */
public class TaskCommandService {

    private final TaskManager taskManager;
    private final TaskCommandPort taskCommands;

    public TaskCommandService(TaskManager taskManager) {
        this.taskManager = Objects.requireNonNull(taskManager, "taskManager");
        this.taskCommands = null;
    }

    public TaskCommandService(TaskCommandPort taskCommands) {
        this.taskManager = null;
        this.taskCommands = Objects.requireNonNull(taskCommands, "taskCommands");
    }

    public Task createTask(TaskCreateRequestDto dto) {
        return taskManager != null ? taskManager.createTask(dto) : taskCommands.createTask(dto);
    }

    public boolean updateTask(Task task) {
        return taskManager != null ? taskManager.updateTask(task) : taskCommands.updateTask(task);
    }

    public boolean deleteTask(String taskId) {
        return taskManager != null ? taskManager.deleteTask(taskId) : taskCommands.deleteTask(taskId);
    }

    public boolean approveTask(String taskId) {
        return taskManager != null ? taskManager.approveTask(taskId) : taskCommands.approveTask(taskId);
    }

    public boolean rejectTask(String taskId) {
        return taskManager != null ? taskManager.rejectTask(taskId) : taskCommands.rejectTask(taskId);
    }

    public boolean blockTask(String taskId) {
        return taskManager != null ? taskManager.blockTask(taskId) : taskCommands.blockTask(taskId);
    }

    public boolean pauseTask(String taskId) {
        return taskManager != null ? taskManager.pauseTask(taskId) : taskCommands.pauseTask(taskId);
    }

    public TaskResumeResult resumeTaskDetailed(String taskId) {
        return taskManager != null ? taskManager.resumeTaskDetailed(taskId) : taskCommands.resumeTaskDetailed(taskId);
    }

    public boolean resumeTask(String taskId) {
        return taskManager != null ? taskManager.resumeTask(taskId) : taskCommands.resumeTask(taskId);
    }

    public boolean cancelTask(String taskId) {
        return taskManager != null ? taskManager.cancelTask(taskId) : taskCommands.cancelTask(taskId);
    }

    public boolean terminateTask(String taskId, TaskTerminalReason reason) {
        return taskManager != null ? taskManager.terminateTask(taskId, reason) : taskCommands.terminateTask(taskId, reason);
    }

    public int appendTaskItems(String taskId, List<Map<String, Object>> inputs) {
        return taskManager != null ? taskManager.appendTaskItems(taskId, inputs) : taskCommands.appendTaskItems(taskId, inputs);
    }

    public boolean sealTask(String taskId) {
        return taskManager != null ? taskManager.sealTask(taskId) : taskCommands.sealTask(taskId);
    }

}

