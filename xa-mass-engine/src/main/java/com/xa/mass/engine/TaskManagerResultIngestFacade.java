package com.xa.mass.engine;

import com.xa.mass.base.runtime.result.TaskResultCorrelation;
import com.xa.mass.base.runtime.result.TaskResultIngestFacade;

import java.util.Map;

/**
 * Transport-facing result ingest adapter that keeps runtime ingress off the
 * full TaskManager facade.
 */
public final class TaskManagerResultIngestFacade implements TaskResultIngestFacade {

    private final TaskResultIngestPort resultIngestPort;

    public TaskManagerResultIngestFacade(TaskResultIngestPort resultIngestPort) {
        this.resultIngestPort = java.util.Objects.requireNonNull(resultIngestPort, "resultIngestPort");
    }

    @Override
    public boolean ingestTaskResult(String taskId,
                                    String messageId,
                                    boolean success,
                                    String detail,
                                    String errorCode,
                                    Map<String, Object> output) {
        return resultIngestPort.ingestTaskResult(taskId, messageId, success, detail, errorCode, output);
    }

    @Override
    public TaskResultCorrelation getResultCorrelation(String taskId, String messageId) {
        return resultIngestPort.getResultCorrelation(taskId, messageId);
    }
}

