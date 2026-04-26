package com.xa.mass.engine.work;

import java.time.Instant;

public record ActiveLeaseRecord(String taskId,
                                String messageId,
                                String leaseToken,
                                String workerId,
                                String workerContextId,
                                String batchId,
                                int retryCount,
                                Instant leaseExpireAt,
                                Instant leasedAt) {
}
