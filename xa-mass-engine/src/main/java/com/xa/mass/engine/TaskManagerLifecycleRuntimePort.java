package com.xa.mass.engine;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.engine.model.TaskTerminalPolicyDecision;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.TaskWorkStats;

import java.util.List;

/**
 * Package-local adapter that keeps lifecycle services off the full TaskManager
 * facade without widening TaskManager's public surface.
 */
final class TaskManagerLifecycleRuntimePort implements TaskLifecycleRuntimePort {

    private final TaskManager taskManager;

    TaskManagerLifecycleRuntimePort(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @Override
    public Task getTask(String taskId) {
        return taskManager.getTask(taskId);
    }

    @Override
    public boolean updateTask(Task task) {
        return taskManager.updateTask(task);
    }

    @Override
    public TaskWorkStats getTaskWorkStats(String taskId) {
        return taskManager.getTaskWorkStats(taskId);
    }

    @Override
    public TaskTerminalPolicyDecision evaluateTerminalPolicy(Task task, TaskWorkStats stats) {
        return taskManager.evaluateTerminalPolicy(task, stats);
    }

    @Override
    public void publishTaskReady(Task task) {
        taskManager.publishTaskReady(task);
    }

    @Override
    public void publishTaskTerminal(Task task) {
        taskManager.publishTaskTerminal(task);
    }

    @Override
    public void publishTaskMessageAttemptClosed(Task task, TaskMsg taskMsg, TaskMsgAttempt attempt) {
        taskManager.publishTaskMessageAttemptClosed(task, taskMsg, attempt);
    }

    @Override
    public void publishTaskMessageLogicallyFinal(Task task, TaskMsg taskMsg) {
        taskManager.publishTaskMessageLogicallyFinal(task, taskMsg);
    }

    @Override
    public void pauseTaskScheduling(String taskId) {
        taskManager.getScheduler().pauseTask(taskId);
    }

    @Override
    public void resumeTaskScheduling(String taskId) {
        taskManager.getScheduler().resumeTask(taskId);
    }

    @Override
    public void cancelTaskScheduling(String taskId) {
        taskManager.getScheduler().cancelTask(taskId);
    }

    @Override
    public void addTaskMessage(String taskId, TaskMsg taskMsg) {
        taskManager.addTaskMessage(taskId, taskMsg);
    }

    @Override
    public void requestTaskDispatch(Task task) {
        taskManager.requestTaskDispatch(task);
    }

    @Override
    public boolean deleteTaskRecord(String taskId) {
        return taskManager.deleteTaskRecord(taskId);
    }

    @Override
    public void discardTaskRuntime(String taskId) {
        taskManager.discardTaskRuntime(taskId);
    }

    @Override
    public List<TaskMsg> getNonFinalTaskMessages(String taskId) {
        return taskManager.getNonFinalTaskMessages(taskId);
    }

    @Override
    public List<ActiveLeaseRecord> getActiveLeases(String taskId) {
        return taskManager.getActiveLeases(taskId);
    }

    @Override
    public boolean updateTaskMessage(String taskId, TaskMsg taskMsg) {
        return taskManager.updateTaskMessage(taskId, taskMsg);
    }

    @Override
    public boolean updateTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt) {
        return taskManager.updateTaskMessageAttempt(taskId, messageId, attempt);
    }

    @Override
    public void addTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt) {
        taskManager.addTaskMessageAttempt(taskId, messageId, attempt);
    }

    @Override
    public TaskMsgAttempt getLatestTaskMessageAttempt(String taskId, String messageId) {
        return taskManager.getLatestTaskMessageAttempt(taskId, messageId);
    }

    @Override
    public TaskMsgAttempt getLatestActiveTaskMessageAttempt(String taskId, String messageId) {
        return taskManager.getLatestActiveTaskMessageAttempt(taskId, messageId);
    }
}

