package com.xa.mass.engine;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.TaskWorkEnvelope;
import com.xa.mass.storage.api.TaskDetailStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Engine owner for bounded TaskMsg / TaskMsgAttempt compatibility projection
 * reads and residue writes.
 */
@CompatibilityProjectionOnly
final class TaskCompatibilityProjectionAccess {

    private static final Logger logger = LoggerFactory.getLogger(TaskCompatibilityProjectionAccess.class);

    private final TaskDetailStore taskDetailStore;
    private final Function<String, Task> taskLookup;
    private final BiFunction<String, String, Optional<ActiveLeaseRecord>> activeLeaseLookup;
    private final BiFunction<String, String, Optional<TaskWorkEnvelope>> runtimeWorkLookup;
    private final Function<String, List<ActiveLeaseRecord>> activeLeasesLookup;

    TaskCompatibilityProjectionAccess(TaskDetailStore taskDetailStore,
                                      Function<String, Task> taskLookup,
                                      BiFunction<String, String, Optional<ActiveLeaseRecord>> activeLeaseLookup,
                                      BiFunction<String, String, Optional<TaskWorkEnvelope>> runtimeWorkLookup,
                                      Function<String, List<ActiveLeaseRecord>> activeLeasesLookup) {
        this.taskDetailStore = Objects.requireNonNull(taskDetailStore, "taskDetailStore");
        this.taskLookup = Objects.requireNonNull(taskLookup, "taskLookup");
        this.activeLeaseLookup = Objects.requireNonNull(activeLeaseLookup, "activeLeaseLookup");
        this.runtimeWorkLookup = Objects.requireNonNull(runtimeWorkLookup, "runtimeWorkLookup");
        this.activeLeasesLookup = Objects.requireNonNull(activeLeasesLookup, "activeLeasesLookup");
    }

    public TaskCompatibilitySnapshotPage visitTaskMessageSnapshot(String taskId,
                                                                  int limit,
                                                                  TaskCompatibilityMessageVisitor visitor) {
        int boundedLimit = Math.max(0, limit);
        List<CompatibilityMessageProjection> stored = boundedLimit == 0
                ? List.of()
                : readStoredMessageProjections(taskId, boundedLimit);
        List<CompatibilityMessageProjection> withActiveLeaseOverlay =
                CompatibilityProjectionSupport.overlayActiveLeaseProjection(
                        stored,
                        activeLeasesLookup.apply(taskId),
                        taskId
                );
        List<CompatibilityMessageProjection> projected =
                CompatibilityProjectionSupport.overlayTerminalTaskProjection(taskLookup.apply(taskId), withActiveLeaseOverlay);
        if (visitor != null) {
            for (CompatibilityMessageProjection projection : projected) {
                emitMessageProjection(projection, visitor);
            }
        }
        boolean truncated = boundedLimit > 0 && taskDetailStore.getTaskMessageStats(taskId).getTotal() > boundedLimit;
        return new TaskCompatibilitySnapshotPage(boundedLimit, truncated, projected.size());
    }

    public boolean visitTaskMessage(String taskId,
                                    String messageId,
                                    TaskCompatibilityMessageVisitor visitor) {
        CompatibilityMessageProjection projection = getVisibleCompatibilityMessageProjection(taskId, messageId);
        if (projection == null) {
            return false;
        }
        if (visitor != null) {
            emitMessageProjection(projection, visitor);
        }
        return true;
    }

    public void visitTaskMessageAttemptViews(String taskId,
                                             String messageId,
                                             TaskCompatibilityMessageAttemptVisitor visitor) {
        if (visitor == null) {
            return;
        }
        for (CompatibilityAttemptProjection projection : readStoredAttemptProjections(taskId, messageId)) {
            emitAttemptProjection(projection, visitor);
        }
    }

    public boolean visitLatestActiveTaskMessageAttempt(String taskId,
                                                       String messageId,
                                                       TaskCompatibilityMessageAttemptVisitor visitor) {
        Task task = taskLookup.apply(taskId);
        if (task != null && task.getStatus() != null && task.getStatus().isFinal()) {
            return false;
        }
        ActiveLeaseRecord activeLease = activeLeaseLookup.apply(taskId, messageId).orElse(null);
        if (activeLease == null) {
            return false;
        }
        CompatibilityMessageProjection storedProjection = getStoredCompatibilityMessageProjection(taskId, messageId);
        TaskMsgStatus messageStatus = storedProjection != null ? storedProjection.status() : TaskMsgStatus.ASSIGNED;
        int attemptNo = Math.max(1, activeLease.retryCount() + 1);
        String attemptId = storedProjection != null ? storedProjection.latestAttemptId() : null;
        if (attemptId == null || attemptId.isBlank()) {
            attemptId = TaskMessageAttemptSupport.runtimeAttemptId(messageId, attemptNo, activeLease);
        }
        java.time.LocalDateTime leaseExpireTime = activeLease.leaseExpireAt() != null
                ? java.time.LocalDateTime.ofInstant(activeLease.leaseExpireAt(), java.time.ZoneId.systemDefault())
                : null;
        java.time.LocalDateTime dispatchTime = activeLease.leasedAt() != null
                ? java.time.LocalDateTime.ofInstant(activeLease.leasedAt(), java.time.ZoneId.systemDefault())
                : null;
        String status = com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus.DISPATCHED.name();
        java.time.LocalDateTime ackTime = null;
        java.time.LocalDateTime startTime = null;
        if (messageStatus == TaskMsgStatus.RUNNING) {
            ackTime = storedProjection != null ? storedProjection.assignedTime() : dispatchTime;
            startTime = storedProjection != null ? storedProjection.startTime() : dispatchTime;
            status = com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus.RUNNING.name();
        }
        java.time.LocalDateTime createTime = dispatchTime;
        java.time.LocalDateTime updateTime = startTime != null ? startTime : ackTime != null ? ackTime : dispatchTime;
        if (visitor != null) {
            visitor.onAttempt(
                    attemptId,
                    taskId,
                    messageId,
                    attemptNo,
                    activeLease.workerId(),
                    activeLease.workerContextId(),
                    activeLease.batchId(),
                    status,
                    leaseExpireTime,
                    dispatchTime,
                    ackTime,
                    startTime,
                    null,
                    null,
                    null,
                    null,
                    null,
                    createTime,
                    updateTime
            );
        }
        return true;
    }

    boolean upsertRuntimeIngressProjection(RuntimeTaskIngressItem ingressItem, String action) {
        if (ingressItem == null) {
            return false;
        }
        return upsertTaskMessageProjection(
                ingressItem.taskId(),
                new CompatibilityMessageProjection(
                        ingressItem.messageId(),
                        ingressItem.taskId(),
                        ingressItem.projectedInput(),
                        ingressItem.payloadRef(),
                        TaskMsgStatus.INIT,
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
                ),
                action
        );
    }

    TaskResultService.RuntimeMessageView getStoredRuntimeMessageProjectionView(String taskId, String messageId) {
        return TaskResultService.RuntimeMessageView.from(getStoredCompatibilityMessageProjection(taskId, messageId));
    }

    boolean upsertTaskMessageProjection(String taskId,
                                        TaskResultService.RuntimeMessageView projection,
                                        String action) {
        if (projection == null) {
            return false;
        }
        return upsertTaskMessageProjection(
                taskId,
                new CompatibilityMessageProjection(
                        projection.messageId(),
                        projection.taskId(),
                        null,
                        projection.payloadRef(),
                        projection.status(),
                        projection.assignedTime(),
                        projection.createTime(),
                        projection.updateTime(),
                        projection.startTime(),
                        projection.completeTime(),
                        projection.retryCount(),
                        projection.maxRetryCount(),
                        projection.errorMessage(),
                        projection.errorCode(),
                        projection.finalReason(),
                        projection.output(),
                        projection.latestAttemptId(),
                        projection.latestAttemptWorkerId(),
                        projection.latestAttemptWorkerContextId(),
                        projection.latestAttemptBatchId()
                ),
                action
        );
    }

    boolean upsertTaskMessageProjection(String taskId,
                                        CompatibilityMessageProjection projection,
                                        String action) {
        if (projection == null) {
            return false;
        }
        return upsertTaskMessageProjectionStorage(taskId, projection, action);
    }

    private boolean upsertTaskMessageProjectionStorage(String taskId,
                                                       CompatibilityMessageProjection projection,
                                                       String action) {
        if (projection == null) {
            return false;
        }
        try {
            return taskDetailStore.upsertTaskMessageProjection(taskId, projection.toStorageProjection());
        } catch (RuntimeException e) {
            logger.warn("Failed to upsert compatibility task message projection for taskId={}, messageId={} during {}",
                    taskId, projection.messageId(), action, e);
            return false;
        }
    }

    void upsertTaskMessageAttemptProjectionBestEffort(String taskId,
                                                      String messageId,
                                                      TaskResultService.AttemptProjectionView projection,
                                                      String action) {
        if (projection == null) {
            return;
        }
        upsertTaskMessageAttemptProjectionBestEffort(
                taskId,
                messageId,
                new CompatibilityAttemptProjection(
                        projection.attemptId(),
                        projection.taskId(),
                        projection.messageId(),
                        projection.attemptNo(),
                        projection.workerId(),
                        projection.workerContextId(),
                        projection.batchId(),
                        projection.status(),
                        projection.finalReason(),
                        projection.errorMessage(),
                        projection.errorCode(),
                        projection.output()
                ),
                action
        );
    }

    void upsertTaskMessageAttemptProjectionBestEffort(String taskId,
                                                      String messageId,
                                                      CompatibilityAttemptProjection projection,
                                                      String action) {
        if (projection == null) {
            return;
        }
        try {
            taskDetailStore.upsertTaskMessageAttemptProjection(taskId, messageId, projection.toStorageProjection());
        } catch (RuntimeException e) {
            logger.warn("Failed to upsert compatibility attempt projection for taskId={}, messageId={}, attemptId={} during {}; runtime result convergence continues",
                    taskId, messageId, projection.attemptId(), action, e);
        }
    }

    CompatibilityAttemptStats getTaskMessageAttemptStats(String taskId, String messageId) {
        return CompatibilityAttemptStats.from(taskDetailStore.getTaskMessageAttemptStats(taskId, messageId));
    }

    @CompatibilityProjectionOnly
    List<CompatibilityMessageProjection> getTaskMessageProjectionsForAudit(String taskId) {
        return readStoredMessageProjections(taskId);
    }

    CompatibilityMessageProjection getStoredCompatibilityMessageProjection(String taskId, String messageId) {
        return CompatibilityMessageProjection.fromStorage(
                taskDetailStore.getTaskMessageProjection(taskId, messageId).orElse(null)
        );
    }

    CompatibilityMessageProjection getVisibleCompatibilityMessageProjection(String taskId, String messageId) {
        Task task = taskLookup.apply(taskId);
        CompatibilityMessageProjection projection = getStoredCompatibilityMessageProjection(taskId, messageId);
        if (projection == null) {
            projection = CompatibilityMessageProjection.fromRuntimeWork(
                    runtimeWorkLookup.apply(taskId, messageId).orElse(null)
            );
        }
        if (task != null && (task.getStatus() == null || !task.getStatus().isFinal())) {
            projection = CompatibilityProjectionSupport.overlayActiveLeaseProjection(
                    projection,
                    activeLeaseLookup.apply(taskId, messageId).orElse(null),
                    taskId,
                    messageId
            );
        }
        return CompatibilityProjectionSupport.overlayTerminalTaskProjection(task, projection);
    }

    private void emitMessageProjection(CompatibilityMessageProjection projection,
                                       TaskCompatibilityMessageVisitor visitor) {
        if (projection == null || visitor == null) {
            return;
        }
        visitor.onMessage(
                projection.messageId(),
                projection.taskId(),
                enumName(projection.status()),
                projection.latestAttemptId(),
                projection.latestAttemptWorkerId(),
                projection.latestAttemptWorkerContextId(),
                projection.latestAttemptBatchId(),
                projection.retryCount(),
                projection.maxRetryCount(),
                projection.errorMessage(),
                projection.errorCode(),
                enumName(projection.finalReason()),
                projection.payloadRef(),
                projection.input(),
                projection.output(),
                projection.assignedTime(),
                projection.createTime(),
                projection.updateTime(),
                projection.startTime(),
                projection.completeTime()
        );
    }

    private void emitAttemptProjection(CompatibilityAttemptProjection projection,
                                       TaskCompatibilityMessageAttemptVisitor visitor) {
        if (projection == null || visitor == null) {
            return;
        }
        visitor.onAttempt(
                projection.attemptId(),
                projection.taskId(),
                projection.messageId(),
                projection.attemptNo(),
                projection.workerId(),
                projection.workerContextId(),
                projection.batchId(),
                enumName(projection.status()),
                null,
                null,
                null,
                null,
                null,
                enumName(projection.finalReason()),
                projection.errorMessage(),
                projection.errorCode(),
                projection.output(),
                null,
                null
        );
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private List<CompatibilityMessageProjection> readStoredMessageProjections(String taskId, int limit) {
        return taskDetailStore.getTaskMessageProjections(taskId, limit).stream()
                .map(CompatibilityMessageProjection::fromStorage)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<CompatibilityMessageProjection> readStoredMessageProjections(String taskId) {
        return taskDetailStore.getTaskMessageProjections(taskId).stream()
                .map(CompatibilityMessageProjection::fromStorage)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<CompatibilityAttemptProjection> readStoredAttemptProjections(String taskId, String messageId) {
        return taskDetailStore.getTaskMessageAttemptProjections(taskId, messageId).stream()
                .map(CompatibilityAttemptProjection::fromStorage)
                .filter(Objects::nonNull)
                .toList();
    }

}
