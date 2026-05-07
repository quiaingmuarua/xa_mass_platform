package com.xa.mass.engine;

import com.xa.mass.base.model.TaskMsgAttempt;

import java.util.Map;
import java.util.Objects;

/**
 * Package-local adapter that keeps transport-facing result ingest off the full
 * {@link TaskManager} facade.
 */
final class TaskManagerResultIngestPort implements TaskResultIngestPort {

    private final TaskManager taskManager;

    TaskManagerResultIngestPort(TaskManager taskManager) {
        this.taskManager = Objects.requireNonNull(taskManager, "taskManager");
    }

    @Override
    public boolean handleTaskMessageResult(String taskId,
                                           String messageId,
                                           boolean success,
                                           String detail,
                                           String errorCode,
                                           Map<String, Object> output) {
        return taskManager.handleTaskMessageResult(taskId, messageId, success, detail, errorCode, output);
    }

    @Override
    public TaskMsgAttempt getLatestActiveTaskMessageAttempt(String taskId, String messageId) {
        return taskManager.getLatestActiveTaskMessageAttempt(taskId, messageId);
    }
}
