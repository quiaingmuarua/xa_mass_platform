package com.xa.mass.engine;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;
import com.xa.mass.storage.api.TaskDetailStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Engine-internal owner for bounded compatibility projection residue over
 * {@link TaskDetailStore}.
 *
 * <p>This keeps storage-edge projection record construction and best-effort
 * residue semantics out of runtime orchestration classes.</p>
 */
@CompatibilityProjectionOnly
final class TaskCompatibilityProjectionStore {

    private static final Logger logger = LoggerFactory.getLogger(TaskCompatibilityProjectionStore.class);

    private final TaskDetailStore taskDetailStore;

    TaskCompatibilityProjectionStore(TaskDetailStore taskDetailStore) {
        this.taskDetailStore = taskDetailStore;
    }

    boolean upsertRuntimeIngressAccepted(RuntimeTaskIngressItem ingressItem) {
        if (ingressItem == null) {
            return false;
        }
        TaskDetailStore.TaskMessageProjection workProjection = new TaskDetailStore.TaskMessageProjection(
                ingressItem.messageId(),
                ingressItem.taskId(),
                ingressItem.projectedInput(),
                ingressItem.payloadRef(),
                TaskWorkProjectionState.MessageStatus.INIT.toProjection(),
                null,
                null,
                null,
                null,
                null,
                ingressItem.retryCount(),
                ingressItem.maxRetryCount(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        try {
            return taskDetailStore.upsertTaskMessageProjection(ingressItem.taskId(), workProjection);
        } catch (RuntimeException e) {
            logger.warn("Failed to upsert compatibility work projection for taskId={}, messageId={} during runtime ingress accepted",
                    ingressItem.taskId(), ingressItem.messageId(), e);
            return false;
        }
    }

    void upsertWorkSummaryBestEffort(String taskId,
                                     WorkSummaryResidue workSummary,
                                     String action) {
        if (workSummary == null) {
            return;
        }
        try {
            if (taskDetailStore.upsertTaskMessageProjection(taskId, toWorkProjectionRecord(workSummary))) {
                return;
            }
        } catch (RuntimeException e) {
            logger.warn("Compatibility work projection write failed for taskId={}, messageId={} during {}; runtime truth already converged",
                    taskId, workSummary.messageId(), action, e);
            return;
        }
        logger.warn("Compatibility work projection write failed for taskId={}, messageId={} during {}; runtime truth already converged",
                taskId, workSummary.messageId(), action);
    }

    void upsertAttemptSummaryBestEffort(String taskId,
                                        String messageId,
                                        WorkAttemptResidue attempt,
                                        String action) {
        if (attempt == null) {
            return;
        }
        try {
            taskDetailStore.upsertTaskMessageAttemptProjection(
                    taskId,
                    messageId,
                    toWorkAttemptProjectionRecord(attempt)
            );
        } catch (RuntimeException e) {
            logger.warn("Failed to upsert compatibility attempt projection for taskId={}, messageId={}, attemptId={} during {}; runtime result convergence continues",
                    taskId, messageId, attempt.attemptId(), action, e);
        }
    }

    long countWorkProjections(String taskId) {
        return taskDetailStore.getTaskMessageStats(taskId).getTotal();
    }

    List<WorkProjectionRecord> getWorkProjections(String taskId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return taskDetailStore.getTaskMessageProjections(taskId, limit).stream()
                .filter(Objects::nonNull)
                .map(WorkProjectionRecord::fromStorage)
                .toList();
    }

    WorkAttemptStatsView getWorkAttemptStats(String taskId, String messageId) {
        TaskDetailStore.TaskMessageAttemptStats stats = taskDetailStore.getTaskMessageAttemptStats(taskId, messageId);
        return new WorkAttemptStatsView(stats.getTotalAttempts(), stats.getActiveAttempts());
    }

    private TaskDetailStore.TaskMessageProjection toWorkProjectionRecord(WorkSummaryResidue workSummary) {
        return new TaskDetailStore.TaskMessageProjection(
                workSummary.messageId(),
                workSummary.taskId(),
                null,
                workSummary.payloadRef(),
                workSummary.status().toProjection(),
                workSummary.assignedTime(),
                workSummary.createTime(),
                workSummary.updateTime(),
                workSummary.startTime(),
                workSummary.completeTime(),
                workSummary.retryCount(),
                workSummary.maxRetryCount(),
                workSummary.errorMessage(),
                workSummary.errorCode(),
                workSummary.finalReason() != null ? workSummary.finalReason().toProjection() : null,
                workSummary.output() == null ? null : new java.util.LinkedHashMap<>(workSummary.output()),
                workSummary.latestAttemptId(),
                workSummary.latestAttemptWorkerId(),
                workSummary.latestAttemptWorkerContextId(),
                workSummary.latestAttemptBatchId()
        );
    }

    private TaskDetailStore.TaskMessageAttemptProjection toWorkAttemptProjectionRecord(WorkAttemptResidue attempt) {
        return new TaskDetailStore.TaskMessageAttemptProjection(
                attempt.attemptId(),
                attempt.taskId(),
                attempt.messageId(),
                attempt.attemptNo(),
                attempt.workerId(),
                attempt.workerContextId(),
                attempt.batchId(),
                attempt.status().toProjection(),
                attempt.finalReason() != null ? attempt.finalReason().toProjection() : null,
                attempt.errorMessage(),
                attempt.errorCode(),
                attempt.output()
        );
    }

    record WorkProjectionRecord(String messageId,
                                TaskWorkProjectionState.MessageStatus status,
                                TaskWorkProjectionState.MessageFinalReason finalReason) {

        static WorkProjectionRecord fromStorage(TaskDetailStore.TaskMessageProjection projection) {
            return new WorkProjectionRecord(
                    projection.messageId(),
                    TaskWorkProjectionState.MessageStatus.fromProjection(projection.status()),
                    TaskWorkProjectionState.MessageFinalReason.fromProjection(projection.finalReason())
            );
        }

        boolean isFinal() {
            return status != null && status.isFinal();
        }
    }

    record WorkAttemptStatsView(long totalAttempts, long activeAttempts) {
    }

    record WorkSummaryResidue(String messageId,
                              String taskId,
                              String latestAttemptId,
                              String latestAttemptWorkerId,
                              String latestAttemptWorkerContextId,
                              String latestAttemptBatchId,
                              TaskWorkProjectionState.MessageStatus status,
                              java.time.LocalDateTime assignedTime,
                              java.time.LocalDateTime createTime,
                              java.time.LocalDateTime updateTime,
                              java.time.LocalDateTime startTime,
                              java.time.LocalDateTime completeTime,
                              int retryCount,
                              int maxRetryCount,
                              String errorMessage,
                              String errorCode,
                              TaskWorkProjectionState.MessageFinalReason finalReason,
                              String payloadRef,
                              java.util.Map<String, Object> output) {
    }

    record WorkAttemptResidue(String attemptId,
                              String taskId,
                              String messageId,
                              int attemptNo,
                              String workerId,
                              String workerContextId,
                              String batchId,
                              TaskWorkProjectionState.AttemptStatus status,
                              TaskWorkProjectionState.AttemptFinalReason finalReason,
                              String errorMessage,
                              String errorCode,
                              java.util.Map<String, Object> output) {
    }
}
