package com.xa.mass.engine;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.engine.model.TaskTerminalPolicyDecision;
import com.xa.mass.engine.work.ActiveLeaseRecord;
import com.xa.mass.engine.work.TaskWorkStats;

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
        return taskManager.getTaskWorkRuntime().stats(taskId);
    }

    @Override
    public TaskTerminalPolicyDecision evaluateTerminalPolicy(Task task, TaskWorkStats stats) {
        return taskManager.getTaskTerminalPolicy().evaluate(task, stats);
    }

    @Override
    public void publishTaskReady(Task task) {
        taskManager.getEventPublisher().publishTaskReady(task);
    }

    @Override
    public void publishTaskTerminal(Task task) {
        taskManager.getEventPublisher().publishTaskTerminal(task);
    }

    @Override
    public void publishTaskMessageAttemptClosed(Task task, TaskMsg taskMsg, TaskMsgAttempt attempt) {
        taskManager.getEventPublisher().publishTaskMessageAttemptClosed(task, taskMsg, attempt);
    }

    @Override
    public void publishTaskMessageLogicallyFinal(Task task, TaskMsg taskMsg) {
        taskManager.getEventPublisher().publishTaskMessageLogicallyFinal(task, taskMsg);
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
        return taskManager.getTaskStorage().deleteTask(taskId);
    }

    @Override
    public void discardTaskRuntime(String taskId) {
        taskManager.getTaskWorkRuntime().discardTask(taskId);
    }

    @Override
    public List<TaskMsg> getNonFinalTaskMessages(String taskId) {
        return taskManager.getTaskStorage().getNonFinalTaskMessages(taskId);
    }

    @Override
    public List<ActiveLeaseRecord> getActiveLeases(String taskId) {
        return taskManager.getTaskWorkRuntime().activeLeases(taskId);
    }

    @Override
    public boolean updateTaskMessage(String taskId, TaskMsg taskMsg) {
        return taskManager.getTaskStorage().updateTaskMessage(taskId, taskMsg);
    }

    @Override
    public boolean updateTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt) {
        return taskManager.getTaskStorage().updateTaskMessageAttempt(taskId, messageId, attempt);
    }

    @Override
    public void addTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt) {
        taskManager.getTaskStorage().addTaskMessageAttempt(taskId, messageId, attempt);
    }

    @Override
    public TaskMsgAttempt getLatestTaskMessageAttempt(String taskId, String messageId) {
        return taskManager.getTaskStorage().getLatestTaskMessageAttempt(taskId, messageId).orElse(null);
    }

    @Override
    public TaskMsgAttempt getLatestActiveTaskMessageAttempt(String taskId, String messageId) {
        return taskManager.getTaskStorage().getLatestActiveTaskMessageAttempt(taskId, messageId).orElse(null);
    }
}
