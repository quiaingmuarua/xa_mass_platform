package com.xa.mass.engine;

import com.xa.mass.base.model.TaskMsgAttempt;

import java.util.Map;

/**
 * Narrow engine surface for transport-side task result ingestion and
 * attempt-identity validation.
 */
public interface TaskResultIngestFacade {

    boolean handleTaskMessageResult(String taskId,
                                    String messageId,
                                    boolean success,
                                    String detail,
                                    String errorCode,
                                    Map<String, Object> output);

    TaskMsgAttempt getLatestActiveTaskMessageAttempt(String taskId, String messageId);
}
