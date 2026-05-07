package com.xa.mass.engine;

import com.xa.mass.base.runtime.result.TaskResultCorrelation;
import com.xa.mass.base.runtime.result.TaskResultIngestFacade;

import java.util.Map;

/**
 * Transport-facing result ingest adapter that keeps runtime ingress off the
 * full TaskManager facade.
 */
public final class TaskManagerResultIngestFacade implements TaskResultIngestFacade {

    private final TaskManager taskManager;
    private final TaskResultIngestPort resultIngestPort;

    public TaskManagerResultIngestFacade(TaskManager taskManager) {
        this.taskManager = java.util.Objects.requireNonNull(taskManager, "taskManager");
        this.resultIngestPort = null;
    }

    public TaskManagerResultIngestFacade(TaskResultIngestPort resultIngestPort) {
        this.taskManager = null;
        this.resultIngestPort = java.util.Objects.requireNonNull(resultIngestPort, "resultIngestPort");
    }

    @Override
    public boolean handleTaskMessageResult(String taskId,
                                           String messageId,
                                           boolean success,
                                           String detail,
                                           String errorCode,
                                           Map<String, Object> output) {
        return taskManager != null
                ? taskManager.handleTaskMessageResult(taskId, messageId, success, detail, errorCode, output)
                : resultIngestPort.handleTaskMessageResult(taskId, messageId, success, detail, errorCode, output);
    }

    @Override
    public TaskResultCorrelation getResultCorrelation(String taskId, String messageId) {
        if (taskManager != null) {
            return TaskResultCorrelationSupport.fromRuntimeState(
                    taskId,
                    messageId,
                    null,
                    taskManager.getActiveLease(taskId, messageId).orElse(null)
            );
        }
        return resultIngestPort.getResultCorrelation(taskId, messageId);
    }
}

