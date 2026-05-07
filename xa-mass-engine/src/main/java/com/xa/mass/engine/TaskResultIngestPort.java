package com.xa.mass.engine;

import com.xa.mass.base.runtime.result.TaskResultCorrelation;

import java.util.Map;

/**
 * Narrow transport-facing result-ingest surface.
 */
public interface TaskResultIngestPort {

    boolean handleTaskMessageResult(String taskId,
                                    String messageId,
                                    boolean success,
                                    String detail,
                                    String errorCode,
                                    Map<String, Object> output);

    TaskResultCorrelation getResultCorrelation(String taskId, String messageId);
}
