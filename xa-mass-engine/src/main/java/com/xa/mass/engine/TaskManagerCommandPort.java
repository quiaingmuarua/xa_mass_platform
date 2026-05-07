package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskCreateRequestDto;
import com.xa.mass.engine.model.TaskResumeResult;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Package-local adapter that keeps shell-facing task commands off the full
 * {@link TaskManager} facade.
 */
final class TaskManagerCommandPort implements TaskCommandPort {

    private final TaskManager taskManager;

    TaskManagerCommandPort(TaskManager taskManager) {
        this.taskManager = Objects.requireNonNull(taskManager, "taskManager");
    }

    @Override
    public Task createTask(TaskCreateRequestDto dto) {
        return taskManager.createTask(dto);
    }

    @Override
    public boolean updateTask(Task task) {
        return taskManager.updateTask(task);
    }

    @Override
    public boolean deleteTask(String taskId) {
        return taskManager.deleteTask(taskId);
    }

    @Override
    public boolean approveTask(String taskId) {
        return taskManager.approveTask(taskId);
    }

    @Override
    public boolean rejectTask(String taskId) {
        return taskManager.rejectTask(taskId);
    }

    @Override
    public boolean blockTask(String taskId) {
        return taskManager.blockTask(taskId);
    }

    @Override
    public boolean pauseTask(String taskId) {
        return taskManager.pauseTask(taskId);
    }

    @Override
    public TaskResumeResult resumeTaskDetailed(String taskId) {
        return taskManager.resumeTaskDetailed(taskId);
    }

    @Override
    public boolean resumeTask(String taskId) {
        return taskManager.resumeTask(taskId);
    }

    @Override
    public boolean cancelTask(String taskId) {
        return taskManager.cancelTask(taskId);
    }

    @Override
    public boolean terminateTask(String taskId, TaskTerminalReason reason) {
        return taskManager.terminateTask(taskId, reason);
    }

    @Override
    public int appendTaskItems(String taskId, List<Map<String, Object>> inputs) {
        return taskManager.appendTaskItems(taskId, inputs);
    }

    @Override
    public boolean sealTask(String taskId) {
        return taskManager.sealTask(taskId);
    }
}
