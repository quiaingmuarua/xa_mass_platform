package com.xa.mass.engine;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.TaskWorkEnvelope;
import com.xa.mass.runtime.api.TaskWorkStats;
import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionFinalReason;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionStatus;
import com.xa.mass.storage.api.projection.TaskMessageProjectionFinalReason;
import com.xa.mass.storage.api.projection.TaskMessageProjectionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Engine owner for bounded compatibility message/attempt projection reads and
 * residue writes.
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

    SnapshotPage visitTaskMessageSnapshot(String taskId,
                                          int limit,
                                          MessageVisitor visitor) {
        int boundedLimit = Math.max(0, limit);
        List<MessageProjection> stored = boundedLimit == 0
                ? List.of()
                : readStoredMessageProjections(taskId, boundedLimit);
        List<MessageProjection> withActiveLeaseOverlay =
                ProjectionOverlaySupport.overlayActiveLeaseProjection(
                        stored,
                        activeLeasesLookup.apply(taskId),
                        taskId
                );
        List<MessageProjection> projected =
                ProjectionOverlaySupport.overlayTerminalTaskProjection(taskLookup.apply(taskId), withActiveLeaseOverlay);
        long compatibilityProjectionTotal = taskDetailStore.getTaskMessageStats(taskId).getTotal();
        TaskWorkStats runtimeStats = runtimeStatsLookup.apply(taskId);
        long runtimeTotal = runtimeStats != null ? runtimeStats.totalCount() : 0L;
        boolean truncated = projected.size() > boundedLimit
                || Math.max(compatibilityProjectionTotal, runtimeTotal) > boundedLimit;
        List<MessageProjection> boundedProjected = boundedLimit == 0
                ? List.of()
                : projected.stream().limit(boundedLimit).toList();
        if (visitor != null) {
            for (MessageProjection projection : boundedProjected) {
                emitMessageProjection(projection, visitor);
            }
        }
        return new SnapshotPage(boundedLimit, truncated, boundedProjected.size());
    }

    boolean visitTaskMessage(String taskId,
                             String messageId,
                             MessageVisitor visitor) {
        MessageProjection projection = getVisibleCompatibilityMessageProjection(taskId, messageId);
        if (projection == null) {
            return false;
        }
        if (visitor != null) {
            emitMessageProjection(projection, visitor);
        }
        return true;
    }

    void visitTaskMessageAttemptViews(String taskId,
                                      String messageId,
                                      AttemptVisitor visitor) {
        if (visitor == null) {
            return;
        }
        List<AttemptProjection> storedAttempts = readStoredAttemptProjections(taskId, messageId);
        RuntimeActiveAttemptView activeAttempt = materializeRuntimeActiveAttemptView(taskId, messageId);
        boolean emittedActiveAttempt = false;
        for (AttemptProjection projection : storedAttempts) {
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

    boolean visitLatestActiveTaskMessageAttempt(String taskId,
                                                String messageId,
                                                AttemptVisitor visitor) {
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
                new MessageProjection(
                        ingressItem.messageId(),
                        ingressItem.taskId(),
                        ingressItem.projectedInput(),
                        ingressItem.payloadRef(),
                        TaskMessageProjectionStatus.INIT,
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
                new MessageProjection(
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
                                        MessageProjection projection,
                                        String action) {
        if (projection == null) {
            return false;
        }
        return upsertTaskMessageProjectionStorage(taskId, projection, action);
    }

    private boolean upsertTaskMessageProjectionStorage(String taskId,
                                                       MessageProjection projection,
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
                new AttemptProjection(
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
                                                      AttemptProjection projection,
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

    MessageProjection getStoredCompatibilityMessageProjection(String taskId, String messageId) {
        return MessageProjection.fromStorage(
                taskDetailStore.getTaskMessageProjection(taskId, messageId).orElse(null)
        );
    }

    MessageProjection getVisibleCompatibilityMessageProjection(String taskId, String messageId) {
        Task task = taskLookup.apply(taskId);
        TaskWorkEnvelope runtimeWork = runtimeWorkLookup.apply(taskId, messageId).orElse(null);
        MessageProjection projection = null;
        if (task == null || task.getStatus() == null || !task.getStatus().isFinal()) {
            projection = MessageProjection.fromRuntimeWork(runtimeWork);
        }
        if (projection == null) {
            projection = getStoredCompatibilityMessageProjection(taskId, messageId);
        }
        if (projection == null) {
            projection = MessageProjection.fromRuntimeWork(runtimeWork);
        }
        if (task != null && (task.getStatus() == null || !task.getStatus().isFinal())) {
            projection = ProjectionOverlaySupport.overlayActiveLeaseProjection(
                    projection,
                    activeLeaseLookup.apply(taskId, messageId).orElse(null),
                    taskId,
                    messageId
            );
        }
        return ProjectionOverlaySupport.overlayTerminalTaskProjection(task, projection);
    }

    private void emitMessageProjection(MessageProjection projection,
                                       MessageVisitor visitor) {
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

    private void emitAttemptProjection(AttemptProjection projection,
                                       AttemptVisitor visitor) {
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
        LocalDateTime leaseExpireTime = activeLease.leaseExpireAt() != null
                ? LocalDateTime.ofInstant(activeLease.leaseExpireAt(), ZoneId.systemDefault())
                : null;
        LocalDateTime dispatchTime = activeLease.leasedAt() != null
                ? LocalDateTime.ofInstant(activeLease.leasedAt(), ZoneId.systemDefault())
                : null;
        LocalDateTime createTime = dispatchTime;
        LocalDateTime updateTime = dispatchTime;
        return new RuntimeActiveAttemptView(
                attemptId,
                taskId,
                messageId,
                attemptNo,
                activeLease.workerId(),
                activeLease.workerContextId(),
                activeLease.batchId(),
                TaskMessageAttemptProjectionStatus.DISPATCHED.name(),
                leaseExpireTime,
                dispatchTime,
                null,
                null,
                createTime,
                updateTime
        );
    }

    private void emitRuntimeActiveAttempt(RuntimeActiveAttemptView activeAttempt,
                                          AttemptVisitor visitor) {
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

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private List<MessageProjection> readStoredMessageProjections(String taskId, int limit) {
        return taskDetailStore.getTaskMessageProjections(taskId, limit).stream()
                .map(MessageProjection::fromStorage)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<MessageProjection> readStoredMessageProjections(String taskId) {
        long total = taskDetailStore.getTaskMessageStats(taskId).getTotal();
        int boundedLimit = total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
        return taskDetailStore.getTaskMessageProjections(taskId, boundedLimit).stream()
                .map(MessageProjection::fromStorage)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<AttemptProjection> readStoredAttemptProjections(String taskId, String messageId) {
        return taskDetailStore.getTaskMessageAttemptProjections(taskId, messageId).stream()
                .map(AttemptProjection::fromStorage)
                .filter(Objects::nonNull)
                .toList();
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        if (source.isEmpty()) {
            return Map.of();
        }
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private record RuntimeActiveAttemptView(String attemptId,
                                            String taskId,
                                            String messageId,
                                            int attemptNo,
                                            String workerId,
                                            String workerContextId,
                                            String batchId,
                                            String status,
                                            LocalDateTime leaseExpireTime,
                                            LocalDateTime dispatchTime,
                                            LocalDateTime ackTime,
                                            LocalDateTime startTime,
                                            LocalDateTime createTime,
                                            LocalDateTime updateTime) {
    }

    @FunctionalInterface
    interface MessageVisitor {

        void onMessage(String messageId,
                       String taskId,
                       String status,
                       String latestAttemptId,
                       String latestAttemptWorkerId,
                       String latestAttemptWorkerContextId,
                       String latestAttemptBatchId,
                       int retryCount,
                       int maxRetryCount,
                       String errorMessage,
                       String errorCode,
                       String finalReason,
                       String payloadRef,
                       Map<String, Object> input,
                       Map<String, Object> output,
                       LocalDateTime assignedTime,
                       LocalDateTime createTime,
                       LocalDateTime updateTime,
                       LocalDateTime startTime,
                       LocalDateTime completeTime);
    }

    @FunctionalInterface
    interface AttemptVisitor {

        void onAttempt(String attemptId,
                       String taskId,
                       String messageId,
                       int attemptNo,
                       String workerId,
                       String workerContextId,
                       String batchId,
                       String status,
                       LocalDateTime leaseExpireTime,
                       LocalDateTime dispatchTime,
                       LocalDateTime ackTime,
                       LocalDateTime startTime,
                       LocalDateTime finishTime,
                       String finalReason,
                       String errorMessage,
                       String errorCode,
                       Map<String, Object> output,
                       LocalDateTime createTime,
                       LocalDateTime updateTime);
    }

    record SnapshotPage(int limit, boolean truncated, int returned) {

        SnapshotPage {
            limit = Math.max(0, limit);
            returned = Math.max(0, returned);
        }
    }

    @CompatibilityProjectionOnly
    record MessageProjection(String messageId,
                             String taskId,
                             Map<String, Object> input,
                             String payloadRef,
                             TaskMessageProjectionStatus status,
                             LocalDateTime assignedTime,
                             LocalDateTime createTime,
                             LocalDateTime updateTime,
                             LocalDateTime startTime,
                             LocalDateTime completeTime,
                             int retryCount,
                             int maxRetryCount,
                             String errorMessage,
                             String errorCode,
                             TaskMessageProjectionFinalReason finalReason,
                             Map<String, Object> output,
                             String latestAttemptId,
                             String latestAttemptWorkerId,
                             String latestAttemptWorkerContextId,
                             String latestAttemptBatchId) {

        MessageProjection {
            input = copyMap(input);
            output = copyMap(output);
            retryCount = Math.max(0, retryCount);
            maxRetryCount = Math.max(0, maxRetryCount);
        }

        static MessageProjection fromStorage(TaskDetailStore.TaskMessageProjection projection) {
            if (projection == null) {
                return null;
            }
            return new MessageProjection(
                    projection.messageId(),
                    projection.taskId(),
                    projection.input(),
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
            );
        }

        static MessageProjection fromRuntimeWork(TaskWorkEnvelope runtimeWork) {
            if (runtimeWork == null) {
                return null;
            }
            LocalDateTime createdAt = runtimeWork.createdAt() == null
                    ? null
                    : LocalDateTime.ofInstant(runtimeWork.createdAt(), ZoneId.systemDefault());
            return new MessageProjection(
                    runtimeWork.messageId(),
                    runtimeWork.taskId(),
                    Map.of(),
                    runtimeWork.payloadRef(),
                    TaskMessageProjectionStatus.INIT,
                    null,
                    createdAt,
                    createdAt,
                    null,
                    null,
                    runtimeWork.retryCount(),
                    runtimeWork.maxRetryCount(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }

        TaskDetailStore.TaskMessageProjection toStorageProjection() {
            return new TaskDetailStore.TaskMessageProjection(
                    messageId,
                    taskId,
                    input,
                    payloadRef,
                    status,
                    assignedTime,
                    createTime,
                    updateTime,
                    startTime,
                    completeTime,
                    retryCount,
                    maxRetryCount,
                    errorMessage,
                    errorCode,
                    finalReason,
                    output,
                    latestAttemptId,
                    latestAttemptWorkerId,
                    latestAttemptWorkerContextId,
                    latestAttemptBatchId
            );
        }

        boolean isCompleted() {
            return status != null && status.isFinal();
        }
    }

    @CompatibilityProjectionOnly
    record AttemptProjection(String attemptId,
                             String taskId,
                             String messageId,
                             int attemptNo,
                             String workerId,
                             String workerContextId,
                             String batchId,
                             TaskMessageAttemptProjectionStatus status,
                             TaskMessageAttemptProjectionFinalReason finalReason,
                             String errorMessage,
                             String errorCode,
                             Map<String, Object> output) {

        AttemptProjection {
            output = copyMap(output);
            attemptNo = Math.max(0, attemptNo);
        }

        static AttemptProjection fromStorage(TaskDetailStore.TaskMessageAttemptProjection projection) {
            if (projection == null) {
                return null;
            }
            return new AttemptProjection(
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
            );
        }

        TaskDetailStore.TaskMessageAttemptProjection toStorageProjection() {
            return new TaskDetailStore.TaskMessageAttemptProjection(
                    attemptId,
                    taskId,
                    messageId,
                    attemptNo,
                    workerId,
                    workerContextId,
                    batchId,
                    status,
                    finalReason,
                    errorMessage,
                    errorCode,
                    output
            );
        }
    }

    @CompatibilityProjectionOnly
    static final class ProjectionOverlaySupport {

        private ProjectionOverlaySupport() {
        }

        static MessageProjection overlayTerminalTaskProjection(Task task,
                                                               MessageProjection storedProjection) {
            if (task == null || storedProjection == null || isCompleted(storedProjection)) {
                return storedProjection;
            }
            if (task.getStatus() == null || !task.getStatus().isFinal()) {
                return storedProjection;
            }
            TaskTerminalReason terminalReason = task.getTerminalReason();
            if (terminalReason == null) {
                return storedProjection;
            }
            if (terminalReason != TaskTerminalReason.MANUAL_CANCELLED && !terminalReason.isPolicyDrivenStop()) {
                return storedProjection;
            }
            TaskMessageProjectionStatus finalStatus = storedProjection.status() != null && storedProjection.status().isProcessing()
                    ? TaskMessageProjectionStatus.EXPIRED
                    : TaskMessageProjectionStatus.FAILED;
            return new MessageProjection(
                    storedProjection.messageId(),
                    storedProjection.taskId(),
                    storedProjection.input(),
                    storedProjection.payloadRef(),
                    finalStatus,
                    storedProjection.assignedTime(),
                    storedProjection.createTime(),
                    LocalDateTime.now(),
                    storedProjection.startTime(),
                    LocalDateTime.now(),
                    storedProjection.retryCount(),
                    storedProjection.maxRetryCount(),
                    terminalDetail(terminalReason),
                    storedProjection.errorCode(),
                    toMessageFinalReason(terminalReason),
                    null,
                    storedProjection.latestAttemptId(),
                    storedProjection.latestAttemptWorkerId(),
                    storedProjection.latestAttemptWorkerContextId(),
                    storedProjection.latestAttemptBatchId()
            );
        }

        static List<MessageProjection> overlayTerminalTaskProjection(Task task,
                                                                     List<MessageProjection> storedProjections) {
            if (storedProjections == null || storedProjections.isEmpty()) {
                return List.of();
            }
            if (task == null || task.getStatus() == null || !task.getStatus().isFinal()) {
                return List.copyOf(storedProjections);
            }
            List<MessageProjection> projected = new ArrayList<>(storedProjections.size());
            for (MessageProjection storedProjection : storedProjections) {
                projected.add(overlayTerminalTaskProjection(task, storedProjection));
            }
            return List.copyOf(projected);
        }

        static MessageProjection overlayActiveLeaseProjection(MessageProjection storedProjection,
                                                              ActiveLeaseRecord activeLease,
                                                              String taskId,
                                                              String messageId) {
            if (activeLease == null) {
                return storedProjection;
            }
            MessageProjection base = storedProjection != null
                    ? storedProjection
                    : new MessageProjection(
                    messageId,
                    taskId,
                    Map.of(),
                    activeLease.payloadRef(),
                    TaskMessageProjectionStatus.INIT,
                    null,
                    null,
                    null,
                    null,
                    null,
                    0,
                    0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
            if (isCompleted(base)) {
                return base;
            }
            int runtimeAttemptNo = Math.max(1, activeLease.retryCount() + 1);
            String runtimeAttemptId = TaskMessageAttemptSupport.runtimeAttemptId(
                    messageId,
                    runtimeAttemptNo,
                    activeLease
            );
            boolean needsAttemptIdProjection = !java.util.Objects.equals(base.latestAttemptId(), runtimeAttemptId);
            boolean attemptProjectionDiffers = !java.util.Objects.equals(base.latestAttemptWorkerId(), activeLease.workerId())
                    || !java.util.Objects.equals(base.latestAttemptWorkerContextId(), activeLease.workerContextId())
                    || !java.util.Objects.equals(base.latestAttemptBatchId(), activeLease.batchId());
            boolean needsAssignedStatus = base.status() == null || base.status() == TaskMessageProjectionStatus.INIT;
            boolean needsRetryProjection = base.retryCount() != Math.max(0, activeLease.retryCount());
            boolean needsAssignedTime = base.assignedTime() == null && activeLease.leasedAt() != null;
            boolean needsPayloadRefProjection = (base.payloadRef() == null || base.payloadRef().isBlank())
                    && activeLease.payloadRef() != null
                    && !activeLease.payloadRef().isBlank();
            if (!attemptProjectionDiffers
                    && !needsAttemptIdProjection
                    && !needsAssignedStatus
                    && !needsRetryProjection
                    && !needsAssignedTime
                    && !needsPayloadRefProjection) {
                return base;
            }
            LocalDateTime assignedTime = base.assignedTime();
            if (assignedTime == null && activeLease.leasedAt() != null) {
                assignedTime = LocalDateTime.ofInstant(activeLease.leasedAt(), ZoneId.systemDefault());
            }
            return new MessageProjection(
                    base.messageId(),
                    base.taskId(),
                    base.input(),
                    needsPayloadRefProjection ? activeLease.payloadRef() : base.payloadRef(),
                    needsAssignedStatus ? TaskMessageProjectionStatus.ASSIGNED : base.status(),
                    assignedTime,
                    base.createTime(),
                    needsAssignedStatus ? LocalDateTime.now() : base.updateTime(),
                    base.startTime(),
                    base.completeTime(),
                    Math.max(0, activeLease.retryCount()),
                    base.maxRetryCount(),
                    base.errorMessage(),
                    base.errorCode(),
                    base.finalReason(),
                    base.output(),
                    runtimeAttemptId,
                    activeLease.workerId(),
                    activeLease.workerContextId(),
                    activeLease.batchId()
            );
        }

        static List<MessageProjection> overlayActiveLeaseProjection(List<MessageProjection> storedProjections,
                                                                    List<ActiveLeaseRecord> activeLeases,
                                                                    String taskId) {
            if ((storedProjections == null || storedProjections.isEmpty())
                    && (activeLeases == null || activeLeases.isEmpty())) {
                return List.of();
            }
            Map<String, ActiveLeaseRecord> leaseByMessageId = new LinkedHashMap<>();
            if (activeLeases != null) {
                for (ActiveLeaseRecord activeLease : activeLeases) {
                    if (activeLease == null || activeLease.messageId() == null || activeLease.messageId().isBlank()) {
                        continue;
                    }
                    leaseByMessageId.put(activeLease.messageId(), activeLease);
                }
            }
            if ((storedProjections == null || storedProjections.isEmpty()) && leaseByMessageId.isEmpty()) {
                return List.of();
            }
            List<MessageProjection> projected =
                    new ArrayList<>(storedProjections != null ? storedProjections.size() : 0);
            java.util.Set<String> projectedMessageIds = new java.util.LinkedHashSet<>();
            if (storedProjections != null) {
                for (MessageProjection storedProjection : storedProjections) {
                    if (storedProjection == null) {
                        continue;
                    }
                    projectedMessageIds.add(storedProjection.messageId());
                    projected.add(overlayActiveLeaseProjection(
                            storedProjection,
                            leaseByMessageId.get(storedProjection.messageId()),
                            taskId,
                            storedProjection.messageId()
                    ));
                }
            }
            for (ActiveLeaseRecord activeLease : leaseByMessageId.values()) {
                if (activeLease == null
                        || activeLease.messageId() == null
                        || activeLease.messageId().isBlank()
                        || projectedMessageIds.contains(activeLease.messageId())) {
                    continue;
                }
                projected.add(overlayActiveLeaseProjection(
                        null,
                        activeLease,
                        taskId,
                        activeLease.messageId()
                ));
            }
            return List.copyOf(projected);
        }

        private static boolean isCompleted(MessageProjection projection) {
            return projection != null && projection.status() != null && projection.status().isFinal();
        }

        private static TaskMessageProjectionFinalReason toMessageFinalReason(TaskTerminalReason terminalReason) {
            return switch (terminalReason) {
                case MAX_RUNTIME_REACHED -> TaskMessageProjectionFinalReason.TIMEOUT;
                case RETRY_BUDGET_EXHAUSTED -> TaskMessageProjectionFinalReason.RETRY_EXHAUSTED;
                case MANUAL_CANCELLED, SUCCESS_RATE_REACHED -> TaskMessageProjectionFinalReason.MANUAL_CANCELLED;
                default -> TaskMessageProjectionFinalReason.MANUAL_CANCELLED;
            };
        }

        private static String terminalDetail(TaskTerminalReason terminalReason) {
            return switch (terminalReason) {
                case MAX_RUNTIME_REACHED -> "task terminated by max runtime policy";
                case SUCCESS_RATE_REACHED -> "task terminated by success-rate policy";
                case RETRY_BUDGET_EXHAUSTED -> "task terminated by retry-budget policy";
                case MANUAL_CANCELLED -> "task cancelled";
                default -> "task terminated";
            };
        }
    }
}
