package com.xa.mass.engine;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.ResultApplyOutcome;
import com.xa.mass.runtime.api.TaskWorkResult;

import java.util.Optional;

/**
 * Package-local result adapter that keeps callback and retry flows off the
 * TaskManager facade.
 */
final class TaskManagerResultRuntimePort implements TaskResultRuntimePort {

    private final TaskManager taskManager;
    private final TaskProjectionBridge taskProjectionBridge;

    TaskManagerResultRuntimePort(TaskManager taskManager,
                                 TaskProjectionBridge taskProjectionBridge) {
        this.taskManager = taskManager;
        this.taskProjectionBridge = taskProjectionBridge;
    }

    @Override
    public Task getTask(String taskId) {
        return taskManager.getTask(taskId);
    }

    @Override
    public TaskMsg getTaskMessage(String taskId, String messageId) {
        return taskProjectionBridge.getTaskMessage(taskId, messageId);
    }

    @Override
    public void addTaskMessageProjection(String taskId, TaskMsg taskMsg) {
        taskProjectionBridge.addTaskMessage(taskId, taskMsg);
    }

    @Override
    public boolean updateTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt) {
        return taskProjectionBridge.updateTaskMessageAttempt(taskId, messageId, attempt);
    }

    @Override
    public Optional<ActiveLeaseRecord> getActiveLease(String taskId, String messageId) {
        return taskManager.getActiveLease(taskId, messageId);
    }

    @Override
    public ResultApplyOutcome applyTaskWorkResult(TaskWorkResult result) {
        return taskManager.applyTaskWorkResult(result);
    }

    @Override
    public long getTaskMessageLeaseSeconds() {
        return taskManager.getTaskMessageLeaseSeconds();
    }

    @Override
    public void requestTaskRetryDispatch(Task task, long delayMillis) {
        taskManager.requestTaskRetryDispatch(task, delayMillis);
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
    public TaskMsgAttempt getLatestActiveTaskMessageAttempt(String taskId, String messageId) {
        return taskProjectionBridge.getLatestActiveTaskMessageAttempt(taskId, messageId);
    }

    @Override
    public TaskMsgAttempt getLatestTaskMessageAttempt(String taskId, String messageId) {
        return taskProjectionBridge.getLatestTaskMessageAttempt(taskId, messageId);
    }

    @Override
    public void addTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt) {
        taskProjectionBridge.addTaskMessageAttempt(taskId, messageId, attempt);
    }

    @Override
    public boolean updateTaskMessage(String taskId, TaskMsg taskMsg) {
        return taskProjectionBridge.updateTaskMessage(taskId, taskMsg);
    }
}

