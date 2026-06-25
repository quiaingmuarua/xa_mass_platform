package com.xa.mass.runtime.api;

import java.time.Instant;

public record ActiveLeaseRecord(String taskId,
                                String messageId,
                                String leaseToken,
                                String workerId,
                                String workerGroupId,
                                String batchId,
                                String selectionToken,
                                Long scoreBandClaimScore,
                                String payloadRef,
                                int retryCount,
                                Instant leaseExpireAt,
                                Instant leasedAt) {
}

