package com.xa.mass.api.review;

/**
 * Server-owned detail materialization report event.
 *
 * <p>These events feed review/export materialization only. They are not kernel
 * progress, result, or finality truth.</p>
 */
public sealed interface TaskReviewReportEvent
        permits TaskReviewItemsAcceptedEvent, TaskReviewWorkTerminalEvent {

    String taskId();
}
