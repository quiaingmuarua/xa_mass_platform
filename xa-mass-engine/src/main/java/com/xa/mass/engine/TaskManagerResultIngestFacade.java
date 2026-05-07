package com.xa.mass.engine;

import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.base.runtime.result.TaskResultIngestFacade;

import java.util.Map;

/**
 * Transport-facing result ingest adapter that keeps runtime ingress off the
 * full TaskManager facade.
 */
public final class TaskManagerResultIngestFacade implements TaskResultIngestFacade {

    private final TaskResultIngestPort resultIngestPort;

    public TaskManagerResultIngestFacade(TaskManager taskManager) {
        this(new TaskManagerResultIngestPort(taskManager));
    }

    public TaskManagerResultIngestFacade(TaskResultIngestPort resultIngestPort) {
        this.resultIngestPort = resultIngestPort;
    }

    @Override
    public boolean handleTaskMessageResult(String taskId,
                                           String messageId,
                                           boolean success,
                                           String detail,
                                           String errorCode,
                                           Map<String, Object> output) {
        return resultIngestPort.handleTaskMessageResult(taskId, messageId, success, detail, errorCode, output);
    }

    @Override
    public TaskMsgAttempt getLatestActiveTaskMessageAttempt(String taskId, String messageId) {
        return resultIngestPort.getLatestActiveTaskMessageAttempt(taskId, messageId);
    }
}
