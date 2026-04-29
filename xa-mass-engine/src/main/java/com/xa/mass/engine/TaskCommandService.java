package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.TaskCreateRequestDto;
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

    public TaskCommandService(TaskManager taskManager) {
        this.taskManager = Objects.requireNonNull(taskManager, "taskManager");
    }

    public Task createTask(TaskCreateRequestDto dto) {
        return taskManager.createTask(dto);
    }

    public boolean updateTask(Task task) {
        return taskManager.updateTask(task);
    }

    public boolean deleteTask(String taskId) {
        return taskManager.deleteTask(taskId);
    }

    public boolean approveTask(String taskId) {
        return taskManager.approveTask(taskId);
    }

    public boolean rejectTask(String taskId) {
        return taskManager.rejectTask(taskId);
    }

    public boolean blockTask(String taskId) {
        return taskManager.blockTask(taskId);
    }

    public boolean pauseTask(String taskId) {
        return taskManager.pauseTask(taskId);
    }

    public TaskResumeResult resumeTaskDetailed(String taskId) {
        return taskManager.resumeTaskDetailed(taskId);
    }

    public boolean resumeTask(String taskId) {
        return taskManager.resumeTask(taskId);
    }

    public boolean cancelTask(String taskId) {
        return taskManager.cancelTask(taskId);
    }

    public boolean terminateTask(String taskId, TaskTerminalReason reason) {
        return taskManager.terminateTask(taskId, reason);
    }

    public int appendTaskItems(String taskId, List<Map<String, Object>> inputs) {
        return taskManager.appendTaskItems(taskId, inputs);
    }

    public boolean sealTask(String taskId) {
        return taskManager.sealTask(taskId);
    }

}
