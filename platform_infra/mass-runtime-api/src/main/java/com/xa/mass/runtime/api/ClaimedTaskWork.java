package com.xa.mass.runtime.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record ClaimedTaskWork(String taskId,
                              String messageId,
                              String leaseToken,
                              String workerId,
                              String workerContextId,
                              String batchId,
                              String eventCode,
                              Map<String, Object> payload,
                              String payloadRef,
                              int retryCount,
                              Instant leaseExpireAt) {

    public ClaimedTaskWork {
        payload = payload == null || payload.isEmpty() ? Map.of() : Map.copyOf(new LinkedHashMap<>(payload));
    }
}

