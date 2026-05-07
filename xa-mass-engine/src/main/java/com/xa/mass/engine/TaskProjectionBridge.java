package com.xa.mass.engine;

import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.storage.api.TaskDetailStore;

import java.util.List;
import java.util.Objects;

/**
 * Engine-internal bridge over the bounded compatibility projection seam.
 *
 * <p>This keeps {@link TaskDetailStore} access explicit for assignment,
 * lifecycle, result, and bounded query flows without re-expanding
 * {@link TaskManager} into the default projection reach-through surface.</p>
 */
final class TaskProjectionBridge {

    private final TaskDetailStore taskDetailStore;

    TaskProjectionBridge(TaskDetailStore taskDetailStore) {
        this.taskDetailStore = Objects.requireNonNull(taskDetailStore, "taskDetailStore");
    }

    void addTaskMessage(String taskId, TaskMsg taskMsg) {
        taskDetailStore.addTaskMessage(taskId, taskMsg);
    }

    List<TaskMsg> getTaskMessages(String taskId) {
        return taskDetailStore.getTaskMessages(taskId);
    }

    List<TaskMsg> getTaskMessages(String taskId, int limit) {
        return taskDetailStore.getTaskMessages(taskId, limit);
    }

    long countTaskMessages(String taskId) {
        return taskDetailStore.countTaskMessages(taskId);
    }

    TaskMsg getTaskMessage(String taskId, String messageId) {
        return taskDetailStore.getTaskMessage(taskId, messageId).orElse(null);
    }

    boolean updateTaskMessage(String taskId, TaskMsg taskMsg) {
        return taskDetailStore.updateTaskMessage(taskId, taskMsg);
    }

    void addTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt) {
        taskDetailStore.addTaskMessageAttempt(taskId, messageId, attempt);
    }

    List<TaskMsgAttempt> getTaskMessageAttempts(String taskId, String messageId) {
        return taskDetailStore.getTaskMessageAttempts(taskId, messageId);
    }

    TaskMsgAttempt getLatestTaskMessageAttempt(String taskId, String messageId) {
        return taskDetailStore.getLatestTaskMessageAttempt(taskId, messageId).orElse(null);
    }

    TaskMsgAttempt getLatestActiveTaskMessageAttempt(String taskId, String messageId) {
        return taskDetailStore.getLatestActiveTaskMessageAttempt(taskId, messageId).orElse(null);
    }

    boolean updateTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt) {
        return taskDetailStore.updateTaskMessageAttempt(taskId, messageId, attempt);
    }

    TaskDetailStore.TaskMessageStats getTaskMessageStats(String taskId) {
        return taskDetailStore.getTaskMessageStats(taskId);
    }

    List<TaskMsg> getNonFinalTaskMessages(String taskId) {
        return taskDetailStore.getNonFinalTaskMessages(taskId);
    }

    TaskDetailStore.TaskMessageAttemptStats getTaskMessageAttemptStats(String taskId, String messageId) {
        return taskDetailStore.getTaskMessageAttemptStats(taskId, messageId);
    }
}
