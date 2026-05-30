package com.xa.mass.api.review;

import com.xa.mass.sdk.model.TaskItemBatchAppendReceipt;
import com.xa.mass.sdk.model.TaskWorkAttemptClosedNotification;
import com.xa.mass.sdk.model.TaskWorkFinalNotification;

import java.util.List;
import java.util.Map;

/**
 * Server-side writer for the task review read model.
 */
public interface TaskReviewReadModelWriter {

    void recordItemsAccepted(String taskId,
                             List<Map<String, Object>> acceptedItems,
                             TaskItemBatchAppendReceipt receipt,
                             int maxRetryCount);

    void recordAttemptClosed(TaskWorkAttemptClosedNotification notification);

    void recordWorkFinal(TaskWorkFinalNotification notification);
}
