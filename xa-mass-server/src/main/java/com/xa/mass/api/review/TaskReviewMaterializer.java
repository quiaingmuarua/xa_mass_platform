package com.xa.mass.api.review;

/**
 * Applies one queued review materialization event to its backing store.
 */
public interface TaskReviewMaterializer {

    void apply(TaskReviewReportEvent event);
}
