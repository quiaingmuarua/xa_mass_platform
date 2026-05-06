package com.xa.mass.base.runtime.result;

import com.xa.mass.base.model.TaskMsgAttempt;

import java.util.Map;

/**
 * Narrow runtime-facing surface for task result ingestion and active-attempt
 * identity validation.
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
