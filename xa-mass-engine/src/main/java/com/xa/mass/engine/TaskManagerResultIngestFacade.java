package com.xa.mass.engine;

import com.xa.mass.base.model.TaskMsgAttempt;

import java.util.Map;

/**
 * Transport-facing result ingest adapter that keeps runtime ingress off the
 * full TaskManager facade.
 */
public final class TaskManagerResultIngestFacade implements TaskResultIngestFacade {

    private final TaskManager taskManager;

    public TaskManagerResultIngestFacade(TaskManager taskManager) {
        this.taskManager = taskManager;
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
        return taskManager.getTaskStorage().getLatestActiveTaskMessageAttempt(taskId, messageId).orElse(null);
    }
}
