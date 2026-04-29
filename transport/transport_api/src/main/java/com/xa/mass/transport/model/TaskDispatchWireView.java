package com.xa.mass.transport.model;

import java.util.Map;

/**
 * Worker-facing canonical dispatch payload view assembled from
 * {@link TaskDispatchItem}. Adapters should prefer this view over reaching into
 * the hybrid item for each field independently.
 */
public record TaskDispatchWireView(String taskId,
                                   String messageId,
                                   String eventCode,
                                   String taskName,
                                   String project,
                                   String userId,
                                   int retryCount,
                                   String workerId,
                                   String workerContextId,
                                   String batchId,
                                   Map<String, Object> input,
                                   Map<String, Object> sharedConfig) {
}
