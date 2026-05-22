package com.xa.mass.base.runtime.result;

import java.util.Map;

/**
 * Narrow runtime-facing surface for task result ingestion and runtime-first
 * envelope correlation.
 */
public interface TaskResultIngestFacade {

    boolean ingestTaskResult(String taskId,
                             String messageId,
                             boolean success,
                             String detail,
                             String errorCode,
                             Map<String, Object> output);

    TaskResultCorrelation getResultCorrelation(String taskId, String messageId);
}
