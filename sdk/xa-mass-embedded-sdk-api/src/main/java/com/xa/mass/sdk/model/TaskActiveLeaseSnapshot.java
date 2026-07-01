package com.xa.mass.sdk.model;

import java.time.Instant;

/**
 * SDK-owned active-lease evidence snapshot for diagnostics.
 */
public record TaskActiveLeaseSnapshot(String taskId,
                                      String messageId,
                                      String workerId,
                                      String batchId,
                                      String payloadRef,
                                      int retryCount,
                                      Instant leaseExpireAt,
                                      Instant leasedAt) {

    public TaskActiveLeaseSnapshot {
        retryCount = Math.max(0, retryCount);
    }
}
