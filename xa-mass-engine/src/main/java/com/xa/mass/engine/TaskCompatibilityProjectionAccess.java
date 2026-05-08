package com.xa.mass.engine;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.TaskWorkEnvelope;
import com.xa.mass.runtime.api.TaskWorkStats;
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
    private final Function<String, TaskWorkStats> runtimeStatsLookup;

    TaskCompatibilityProjectionAccess(TaskDetailStore taskDetailStore,
                                      Function<String, Task> taskLookup,
                                      BiFunction<String, String, Optional<ActiveLeaseRecord>> activeLeaseLookup,
                                      BiFunction<String, String, Optional<TaskWorkEnvelope>> runtimeWorkLookup,
                                      Function<String, List<ActiveLeaseRecord>> activeLeasesLookup,
                                      Function<String, TaskWorkStats> runtimeStatsLookup) {
        this.taskDetailStore = Objects.requireNonNull(taskDetailStore, "taskDetailStore");
        this.taskLookup = Objects.requireNonNull(taskLookup, "taskLookup");
        this.activeLeaseLookup = Objects.requireNonNull(activeLeaseLookup, "activeLeaseLookup");
        this.runtimeWorkLookup = Objects.requireNonNull(runtimeWorkLookup, "runtimeWorkLookup");
        this.activeLeasesLookup = Objects.requireNonNull(activeLeasesLookup, "activeLeasesLookup");
        this.runtimeStatsLookup = Objects.requireNonNull(runtimeStatsLookup, "runtimeStatsLookup");
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
        long compatibilityProjectionTotal = taskDetailStore.getTaskMessageStats(taskId).getTotal();
        TaskWorkStats runtimeStats = runtimeStatsLookup.apply(taskId);
        long runtimeTotal = runtimeStats != null ? runtimeStats.totalCount() : 0L;
        boolean truncated = projected.size() > boundedLimit
                || Math.max(compatibilityProjectionTotal, runtimeTotal) > boundedLimit;
        List<CompatibilityMessageProjection> boundedProjected = boundedLimit == 0
                ? List.of()
                : projected.stream().limit(boundedLimit).toList();
        if (visitor != null) {
            for (CompatibilityMessageProjection projection : boundedProjected) {
                emitMessageProjection(projection, visitor);
            }
        }
        return new TaskCompatibilitySnapshotPage(boundedLimit, truncated, boundedProjected.size());
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
        List<CompatibilityAttemptProjection> storedAttempts = readStoredAttemptProjections(taskId, messageId);
        RuntimeActiveAttemptView activeAttempt = materializeRuntimeActiveAttemptView(taskId, messageId);
        boolean emittedActiveAttempt = false;
        for (CompatibilityAttemptProjection projection : storedAttempts) {
            if (activeAttempt != null && activeAttempt.attemptId().equals(projection.attemptId())) {
                emitRuntimeActiveAttempt(activeAttempt, visitor);
                emittedActiveAttempt = true;
                continue;
            }
            emitAttemptProjection(projection, visitor);
        }
        if (activeAttempt != null && !emittedActiveAttempt) {
            emitRuntimeActiveAttempt(activeAttempt, visitor);
        }
    }

    public boolean visitLatestActiveTaskMessageAttempt(String taskId,
                                                       String messageId,
                                                       TaskCompatibilityMessageAttemptVisitor visitor) {
        Task task = taskLookup.apply(taskId);
        if (task != null && task.getStatus() != null && task.getStatus().isFinal()) {
            return false;
        }
        RuntimeActiveAttemptView activeAttempt = materializeRuntimeActiveAttemptView(taskId, messageId);
        if (activeAttempt == null) {
            return false;
        }
        emitRuntimeActiveAttempt(activeAttempt, visitor);
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
        TaskWorkEnvelope runtimeWork = runtimeWorkLookup.apply(taskId, messageId).orElse(null);
        CompatibilityMessageProjection projection = null;
        if (task == null || task.getStatus() == null || !task.getStatus().isFinal()) {
            projection = CompatibilityMessageProjection.fromRuntimeWork(runtimeWork);
        }
        if (projection == null) {
            projection = getStoredCompatibilityMessageProjection(taskId, messageId);
        }
        if (projection == null) {
            projection = CompatibilityMessageProjection.fromRuntimeWork(runtimeWork);
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

    private RuntimeActiveAttemptView materializeRuntimeActiveAttemptView(String taskId, String messageId) {
        Task task = taskLookup.apply(taskId);
        if (task != null && task.getStatus() != null && task.getStatus().isFinal()) {
            return null;
        }
        ActiveLeaseRecord activeLease = activeLeaseLookup.apply(taskId, messageId).orElse(null);
        if (activeLease == null) {
            return null;
        }
        int attemptNo = Math.max(1, activeLease.retryCount() + 1);
        String attemptId = TaskMessageAttemptSupport.runtimeAttemptId(messageId, attemptNo, activeLease);
        CompatibilityMessageProjection storedProjection = getStoredCompatibilityMessageProjection(taskId, messageId);
        boolean runningAttempt = isProjectedRunningAttempt(storedProjection, attemptId);
        java.time.LocalDateTime leaseExpireTime = activeLease.leaseExpireAt() != null
                ? java.time.LocalDateTime.ofInstant(activeLease.leaseExpireAt(), java.time.ZoneId.systemDefault())
                : null;
        java.time.LocalDateTime dispatchTime = activeLease.leasedAt() != null
                ? java.time.LocalDateTime.ofInstant(activeLease.leasedAt(), java.time.ZoneId.systemDefault())
                : null;
        java.time.LocalDateTime ackTime = runningAttempt
                ? storedProjection != null ? storedProjection.assignedTime() : dispatchTime
                : null;
        java.time.LocalDateTime startTime = runningAttempt
                ? storedProjection != null ? storedProjection.startTime() : dispatchTime
                : null;
        java.time.LocalDateTime createTime = dispatchTime;
        java.time.LocalDateTime updateTime = startTime != null ? startTime : ackTime != null ? ackTime : dispatchTime;
        return new RuntimeActiveAttemptView(
                attemptId,
                taskId,
                messageId,
                attemptNo,
                activeLease.workerId(),
                activeLease.workerContextId(),
                activeLease.batchId(),
                runningAttempt
                        ? com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus.RUNNING.name()
                        : com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus.DISPATCHED.name(),
                leaseExpireTime,
                dispatchTime,
                ackTime,
                startTime,
                createTime,
                updateTime
        );
    }

    private void emitRuntimeActiveAttempt(RuntimeActiveAttemptView activeAttempt,
                                          TaskCompatibilityMessageAttemptVisitor visitor) {
        if (activeAttempt == null || visitor == null) {
            return;
        }
        visitor.onAttempt(
                activeAttempt.attemptId(),
                activeAttempt.taskId(),
                activeAttempt.messageId(),
                activeAttempt.attemptNo(),
                activeAttempt.workerId(),
                activeAttempt.workerContextId(),
                activeAttempt.batchId(),
                activeAttempt.status(),
                activeAttempt.leaseExpireTime(),
                activeAttempt.dispatchTime(),
                activeAttempt.ackTime(),
                activeAttempt.startTime(),
                null,
                null,
                null,
                null,
                null,
                activeAttempt.createTime(),
                activeAttempt.updateTime()
        );
    }

    private boolean isProjectedRunningAttempt(CompatibilityMessageProjection storedProjection, String runtimeAttemptId) {
        if (storedProjection == null || storedProjection.status() != TaskMsgStatus.RUNNING) {
            return false;
        }
        if (runtimeAttemptId == null || runtimeAttemptId.isBlank()) {
            return false;
        }
        return runtimeAttemptId.equals(storedProjection.latestAttemptId());
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

    private record RuntimeActiveAttemptView(String attemptId,
                                            String taskId,
                                            String messageId,
                                            int attemptNo,
                                            String workerId,
                                            String workerContextId,
                                            String batchId,
                                            String status,
                                            java.time.LocalDateTime leaseExpireTime,
                                            java.time.LocalDateTime dispatchTime,
                                            java.time.LocalDateTime ackTime,
                                            java.time.LocalDateTime startTime,
                                            java.time.LocalDateTime createTime,
                                            java.time.LocalDateTime updateTime) {
    }

}
