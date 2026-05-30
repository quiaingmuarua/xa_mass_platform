package com.xa.mass.api.review;

import com.xa.mass.sdk.model.TaskItemBatchAppendReceipt;
import com.xa.mass.sdk.model.TaskWorkFinalNotification;
import com.xa.mass.sdk.model.TaskWorkFinalSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Converts current review write callbacks into best-effort materialization events.
 */
public final class QueueBackedTaskReviewReadModelWriter implements TaskReviewReadModelWriter {

    private static final Logger log = LoggerFactory.getLogger(QueueBackedTaskReviewReadModelWriter.class);

    private final TaskReviewReportQueue queue;

    public QueueBackedTaskReviewReadModelWriter(TaskReviewReportQueue queue) {
        this.queue = Objects.requireNonNull(queue, "queue");
    }

    @Override
    public void recordItemsAccepted(String taskId,
                                    List<Map<String, Object>> acceptedItems,
                                    TaskItemBatchAppendReceipt receipt,
                                    int maxRetryCount) {
        if (isBlank(taskId) || acceptedItems == null || receipt == null) {
            return;
        }
        try {
            TaskReviewItemsAcceptedEvent event =
                    TaskReviewItemsAcceptedEvent.from(taskId, acceptedItems, receipt, maxRetryCount);
            if (!queue.submit(event)) {
                log.warn("Task review materialization event rejected: type=itemsAccepted, taskId={}, added={}",
                        taskId, receipt.added());
            }
        } catch (RuntimeException e) {
            log.warn("Task review materialization event creation failed: type=itemsAccepted, taskId={}, reason={}",
                    taskId, e.getMessage(), e);
        }
    }

    @Override
    public void recordWorkFinal(TaskWorkFinalNotification notification) {
        TaskWorkFinalSnapshot snapshot = notification == null ? null : notification.finalSnapshot();
        if (snapshot == null || isBlank(snapshot.taskId()) || isBlank(snapshot.messageId())) {
            return;
        }
        try {
            TaskReviewWorkTerminalEvent event = TaskReviewWorkTerminalEvent.from(snapshot);
            if (!queue.submit(event)) {
                log.warn("Task review materialization event rejected: type=workTerminal, taskId={}, messageId={}",
                        snapshot.taskId(), snapshot.messageId());
            }
        } catch (RuntimeException e) {
            log.warn("Task review materialization event creation failed: type=workTerminal, taskId={}, messageId={}, reason={}",
                    snapshot.taskId(), snapshot.messageId(), e.getMessage(), e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
