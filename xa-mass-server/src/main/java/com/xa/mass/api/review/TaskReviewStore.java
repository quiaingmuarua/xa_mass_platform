package com.xa.mass.api.review;

import com.xa.mass.api.review.TaskReviewReadModel.TaskReviewAttempt;
import com.xa.mass.api.review.TaskReviewReadModel.TaskReviewItem;
import com.xa.mass.api.review.TaskReviewReadModel.TaskReviewStats;

import java.util.List;
import java.util.Optional;

/**
 * Server-local backing store for review/export materialization rows.
 *
 * <p>This is not runtime task truth. It may lag runtime queues and must not be
 * used for scheduling, lease, retry, terminal policy, or progress decisions.</p>
 */
public interface TaskReviewStore {

    boolean upsertItem(String taskId, TaskReviewItem item);

    Optional<TaskReviewItem> findItem(String taskId, String messageId);

    List<TaskReviewItem> listItems(String taskId, int limit);

    boolean upsertAttempt(String taskId, String messageId, TaskReviewAttempt attempt);

    List<TaskReviewAttempt> listAttempts(String taskId, String messageId);

    TaskReviewStats stats(String taskId);
}
