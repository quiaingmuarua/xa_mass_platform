package com.xa.mass.api.review;

import java.time.Duration;

/**
 * Best-effort server-local queue for review/export materialization reports.
 */
public interface TaskReviewReportQueue extends AutoCloseable {

    boolean submit(TaskReviewReportEvent event);

    boolean awaitIdle(Duration timeout);

    TaskReviewReportQueueStats snapshotStats();

    @Override
    void close();
}
