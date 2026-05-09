package com.xa.mass.engine;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;
import com.xa.mass.storage.api.TaskDetailStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

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
        TaskDetailStore.TaskMessageProjection projection = new TaskDetailStore.TaskMessageProjection(
                ingressItem.messageId(),
                ingressItem.taskId(),
                ingressItem.projectedInput(),
                ingressItem.payloadRef(),
                TaskMessageCompatibilityState.MessageStatus.INIT.toProjection(),
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
            return taskDetailStore.upsertTaskMessageProjection(ingressItem.taskId(), projection);
        } catch (RuntimeException e) {
            logger.warn("Failed to upsert compatibility task message projection for taskId={}, messageId={} during runtime ingress accepted",
                    ingressItem.taskId(), ingressItem.messageId(), e);
            return false;
        }
    }

    void upsertTaskMessageSummaryBestEffort(String taskId,
                                            TaskResultService.RuntimeMessageView taskMsg,
                                            String action) {
        if (taskMsg == null) {
            return;
        }
        try {
            if (taskDetailStore.upsertTaskMessageProjection(taskId, toTaskMessageProjectionRecord(taskMsg))) {
                return;
            }
        } catch (RuntimeException e) {
            logger.warn("Compatibility task message projection write failed for taskId={}, messageId={} during {}; runtime truth already converged",
                    taskId, taskMsg.messageId(), action, e);
            return;
        }
        logger.warn("Compatibility task message projection write failed for taskId={}, messageId={} during {}; runtime truth already converged",
                taskId, taskMsg.messageId(), action);
    }

    void upsertAttemptSummaryBestEffort(String taskId,
                                        String messageId,
                                        TaskResultService.AttemptProjectionView attempt,
                                        String action) {
        if (attempt == null) {
            return;
        }
        try {
            taskDetailStore.upsertTaskMessageAttemptProjection(
                    taskId,
                    messageId,
                    toTaskMessageAttemptProjectionRecord(attempt)
            );
        } catch (RuntimeException e) {
            logger.warn("Failed to upsert compatibility attempt projection for taskId={}, messageId={}, attemptId={} during {}; runtime result convergence continues",
                    taskId, messageId, attempt.attemptId(), action, e);
        }
    }

    long countTaskMessageProjections(String taskId) {
        return taskDetailStore.getTaskMessageStats(taskId).getTotal();
    }

    List<TaskDetailStore.TaskMessageProjection> getTaskMessageProjections(String taskId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return taskDetailStore.getTaskMessageProjections(taskId, limit);
    }

    TaskDetailStore.TaskMessageAttemptStats getTaskMessageAttemptStats(String taskId, String messageId) {
        return taskDetailStore.getTaskMessageAttemptStats(taskId, messageId);
    }

    private TaskDetailStore.TaskMessageProjection toTaskMessageProjectionRecord(TaskResultService.RuntimeMessageView taskMsg) {
        return new TaskDetailStore.TaskMessageProjection(
                taskMsg.messageId(),
                taskMsg.taskId(),
                null,
                taskMsg.payloadRef(),
                taskMsg.status().toProjection(),
                taskMsg.assignedTime(),
                taskMsg.createTime(),
                taskMsg.updateTime(),
                taskMsg.startTime(),
                taskMsg.completeTime(),
                taskMsg.retryCount(),
                taskMsg.maxRetryCount(),
                taskMsg.errorMessage(),
                taskMsg.errorCode(),
                taskMsg.finalReason() != null ? taskMsg.finalReason().toProjection() : null,
                taskMsg.output() == null ? null : new java.util.LinkedHashMap<>(taskMsg.output()),
                taskMsg.latestAttemptId(),
                taskMsg.latestAttemptWorkerId(),
                taskMsg.latestAttemptWorkerContextId(),
                taskMsg.latestAttemptBatchId()
        );
    }

    private TaskDetailStore.TaskMessageAttemptProjection toTaskMessageAttemptProjectionRecord(TaskResultService.AttemptProjectionView attempt) {
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
}
