package com.xa.mass.engine;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.engine.work.ActiveLeaseRecord;
import com.xa.mass.engine.work.ResultApplyOutcome;
import com.xa.mass.engine.work.TaskWorkResult;

import java.util.Optional;

/**
 * Package-local result adapter that keeps callback and retry flows off the
 * TaskManager facade.
 */
final class TaskManagerResultRuntimePort implements TaskResultRuntimePort {

    private final TaskManager taskManager;

    TaskManagerResultRuntimePort(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @Override
    public Task getTask(String taskId) {
        return taskManager.getTask(taskId);
    }

    @Override
    public TaskMsg getTaskMessage(String taskId, String messageId) {
        return taskManager.getTaskMessage(taskId, messageId);
    }

    @Override
    public boolean updateTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt) {
        return taskManager.updateTaskMessageAttempt(taskId, messageId, attempt);
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
    public void handleTaskMsgCompletion(TaskMsg taskMsg) {
        taskManager.handleTaskMsgCompletion(taskMsg);
    }

    @Override
    public void handleTaskMsgFailure(TaskMsg taskMsg, String detail) {
        taskManager.handleTaskMsgFailure(taskMsg, detail);
    }

    @Override
    public TaskMsgAttempt getLatestActiveTaskMessageAttempt(String taskId, String messageId) {
        return taskManager.getLatestActiveTaskMessageAttempt(taskId, messageId);
    }

    @Override
    public TaskMsgAttempt getLatestTaskMessageAttempt(String taskId, String messageId) {
        return taskManager.getLatestTaskMessageAttempt(taskId, messageId);
    }

    @Override
    public void addTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt) {
        taskManager.addTaskMessageAttempt(taskId, messageId, attempt);
    }

    @Override
    public boolean updateTaskMessage(String taskId, TaskMsg taskMsg) {
        return taskManager.updateTaskMessage(taskId, taskMsg);
    }
}
