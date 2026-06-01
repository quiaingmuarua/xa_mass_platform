package com.xa.mass.api.review;

import com.xa.mass.sdk.model.TaskItemBatchAppendReceipt;
import com.xa.mass.sdk.model.TaskWorkAttemptClosedNotification;
import com.xa.mass.sdk.model.TaskWorkAttemptClosedSnapshot;
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
    private final TaskReviewMaterializationPolicy policy;

    public QueueBackedTaskReviewReadModelWriter(TaskReviewReportQueue queue,
                                                TaskReviewMaterializationPolicy policy) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public void recordItemsAccepted(String taskId,
                                    Map<String, Object> sharedConfig,
                                    List<Map<String, Object>> acceptedItems,
                                    TaskItemBatchAppendReceipt receipt,
                                    int maxRetryCount) {
        if (isBlank(taskId) || acceptedItems == null || receipt == null) {
            return;
        }
        TaskReviewMaterializationMode mode = policy.modeFor(sharedConfig);
        if (!mode.recordsTerminalFacts()) {
            logSkipped("itemsAccepted", taskId, null, null, mode);
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
    public void recordAttemptClosed(TaskWorkAttemptClosedNotification notification) {
        TaskWorkAttemptClosedSnapshot snapshot = notification == null ? null : notification.attemptSnapshot();
        if (snapshot == null || isBlank(snapshot.taskId()) || isBlank(snapshot.messageId())
                || isBlank(snapshot.attemptId())) {
            return;
        }
        Map<String, Object> sharedConfig = notification == null ? Map.of() : notification.sharedConfig();
        TaskReviewMaterializationMode mode = policy.modeFor(sharedConfig);
        if (!mode.recordsDiagnosticFacts()) {
            logSkipped("attemptClosed", snapshot.taskId(), snapshot.messageId(), snapshot.attemptId(), mode);
            return;
        }
        try {
            TaskReviewAttemptClosedEvent event = TaskReviewAttemptClosedEvent.from(snapshot);
            if (!queue.submit(event)) {
                log.warn("Task review materialization event rejected: type=attemptClosed, taskId={}, messageId={}, attemptId={}",
                        snapshot.taskId(), snapshot.messageId(), snapshot.attemptId());
            }
        } catch (RuntimeException e) {
            log.warn("Task review materialization event creation failed: type=attemptClosed, taskId={}, messageId={}, attemptId={}, reason={}",
                    snapshot.taskId(), snapshot.messageId(), snapshot.attemptId(), e.getMessage(), e);
        }
    }

    @Override
    public void recordWorkFinal(TaskWorkFinalNotification notification) {
        TaskWorkFinalSnapshot snapshot = notification == null ? null : notification.finalSnapshot();
        if (snapshot == null || isBlank(snapshot.taskId()) || isBlank(snapshot.messageId())) {
            return;
        }
        Map<String, Object> sharedConfig = notification == null ? Map.of() : notification.sharedConfig();
        TaskReviewMaterializationMode mode = policy.modeFor(sharedConfig);
        if (!mode.recordsTerminalFacts()) {
            logSkipped("workTerminal", snapshot.taskId(), snapshot.messageId(), snapshot.attemptId(), mode);
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

    private void logSkipped(String type,
                            String taskId,
                            String messageId,
                            String attemptId,
                            TaskReviewMaterializationMode mode) {
        TaskReviewReportQueueStats stats = queue.snapshotStats();
        log.debug("Task review materialization skipped by policy: type={}, taskId={}, messageId={}, attemptId={}, mode={}, queueSubmitted={}, queueRejected={}, queuePending={}, queueFailed={}",
                type,
                taskId,
                messageId,
                attemptId,
                mode,
                stats.submitted(),
                stats.rejected(),
                stats.pending(),
                stats.failed());
    }
}
