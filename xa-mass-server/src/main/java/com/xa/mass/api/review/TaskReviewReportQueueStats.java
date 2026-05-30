package com.xa.mass.api.review;

/**
 * Bounded observability snapshot for the best-effort review report queue.
 */
public record TaskReviewReportQueueStats(long submitted,
                                         long rejected,
                                         long applied,
                                         long failed,
                                         long pending,
                                         String lastError) {
}
