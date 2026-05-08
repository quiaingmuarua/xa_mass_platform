package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
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

    private final TaskCommandPort taskCommands;

    public TaskCommandService(TaskCommandPort taskCommands) {
        this.taskCommands = Objects.requireNonNull(taskCommands, "taskCommands");
    }

    public Task createTaskShell(TaskShellCreateRequestDto dto) {
        return taskCommands.createTaskShell(dto);
    }

    public boolean updateTask(Task task) {
        return taskCommands.updateTask(task);
    }

    public boolean deleteTask(String taskId) {
        return taskCommands.deleteTask(taskId);
    }

    public boolean approveTask(String taskId) {
        return taskCommands.approveTask(taskId);
    }

    public boolean rejectTask(String taskId) {
        return taskCommands.rejectTask(taskId);
    }

    public boolean blockTask(String taskId) {
        return taskCommands.blockTask(taskId);
    }

    public boolean pauseTask(String taskId) {
        return taskCommands.pauseTask(taskId);
    }

    public TaskResumeResult resumeTaskDetailed(String taskId) {
        return taskCommands.resumeTaskDetailed(taskId);
    }

    public boolean resumeTask(String taskId) {
        return taskCommands.resumeTask(taskId);
    }

    public boolean cancelTask(String taskId) {
        return taskCommands.cancelTask(taskId);
    }

    public boolean terminateTask(String taskId, TaskTerminalReason reason) {
        return taskCommands.terminateTask(taskId, reason);
    }

    public int appendTaskItems(String taskId, List<Map<String, Object>> inputs) {
        return taskCommands.appendTaskItems(taskId, inputs);
    }

    public int appendTaskItems(String taskId, List<Map<String, Object>> inputs, int defaultMsgMaxRetryCount) {
        return taskCommands.appendTaskItems(taskId, inputs, defaultMsgMaxRetryCount);
    }

    public boolean sealTask(String taskId) {
        return taskCommands.sealTask(taskId);
    }

}

