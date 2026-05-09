package com.xa.mass.runtime.api;

import java.time.Instant;

/**
 * Bounded runtime-owned receipt for recently finalized work.
 *
 * <p>This is not a historical query surface. It only keeps the minimal fields
 * needed to classify duplicate or late callbacks after the active lease and
 * queue work have already been removed.</p>
 */
public record RecentFinalWorkReceipt(String taskId,
                                     String messageId,
                                     TaskWorkFinalStatus status,
                                     String errorCode,
                                     int retryCount,
                                     Instant completedAt) {

    public RecentFinalWorkReceipt {
        completedAt = completedAt == null ? Instant.now() : completedAt;
    }
}
