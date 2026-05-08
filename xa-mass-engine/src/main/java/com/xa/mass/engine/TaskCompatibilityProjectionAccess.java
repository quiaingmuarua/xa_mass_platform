package com.xa.mass.engine;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMessageSnapshot;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
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
final class TaskCompatibilityProjectionAccess implements TaskCompatibilityQueryPort {

    private static final Logger logger = LoggerFactory.getLogger(TaskCompatibilityProjectionAccess.class);

    private final TaskDetailStore taskDetailStore;
    private final Function<String, Task> taskLookup;
    private final BiFunction<String, String, Optional<ActiveLeaseRecord>> activeLeaseLookup;
    private final Function<String, List<ActiveLeaseRecord>> activeLeasesLookup;

    TaskCompatibilityProjectionAccess(TaskDetailStore taskDetailStore,
                                      Function<String, Task> taskLookup,
                                      BiFunction<String, String, Optional<ActiveLeaseRecord>> activeLeaseLookup,
                                      Function<String, List<ActiveLeaseRecord>> activeLeasesLookup) {
        this.taskDetailStore = Objects.requireNonNull(taskDetailStore, "taskDetailStore");
        this.taskLookup = Objects.requireNonNull(taskLookup, "taskLookup");
        this.activeLeaseLookup = Objects.requireNonNull(activeLeaseLookup, "activeLeaseLookup");
        this.activeLeasesLookup = Objects.requireNonNull(activeLeasesLookup, "activeLeasesLookup");
    }

    @Override
    public TaskMessageSnapshot getTaskMessageSnapshot(String taskId, int limit) {
        int boundedLimit = Math.max(0, limit);
        List<TaskDetailStore.TaskMessageProjection> stored = boundedLimit == 0
                ? List.of()
                : taskDetailStore.getTaskMessageProjections(taskId, boundedLimit);
        List<TaskDetailStore.TaskMessageProjection> withActiveLeaseOverlay =
                CompatibilityProjectionSupport.overlayActiveLeaseProjection(
                        stored,
                        activeLeasesLookup.apply(taskId),
                        taskId
                );
        List<TaskMsg> projected = materializeCompatibilityTaskMessages(
                CompatibilityProjectionSupport.overlayTerminalTaskProjection(taskLookup.apply(taskId), withActiveLeaseOverlay)
        );
        boolean truncated = boundedLimit > 0 && taskDetailStore.getTaskMessageStats(taskId).getTotal() > boundedLimit;
        return new TaskMessageSnapshot(projected, boundedLimit, truncated);
    }

    @Override
    public TaskMsg getTaskMessageView(String taskId, String messageId) {
        TaskDetailStore.TaskMessageProjection projection = getVisibleTaskMessageProjection(taskId, messageId);
        return projection != null ? projection.toCompatibilityProjection() : null;
    }

    @Override
    public List<TaskMsgAttempt> getTaskMessageAttemptViews(String taskId, String messageId) {
        return taskDetailStore.getTaskMessageAttemptProjections(taskId, messageId).stream()
                .map(TaskDetailStore.TaskMessageAttemptProjection::toCompatibilityProjection)
                .toList();
    }

    @Override
    public TaskMsgAttempt getLatestActiveTaskMessageAttemptView(String taskId, String messageId) {
        Task task = taskLookup.apply(taskId);
        if (task != null && task.getStatus() != null && task.getStatus().isFinal()) {
            return null;
        }
        ActiveLeaseRecord activeLease = activeLeaseLookup.apply(taskId, messageId).orElse(null);
        if (activeLease == null) {
            return null;
        }
        TaskDetailStore.TaskMessageProjection storedProjection = getStoredTaskMessageRecord(taskId, messageId);
        TaskDetailStore.TaskMessageAttemptProjection latestAuditView =
                taskDetailStore.getLatestTaskMessageAttemptProjection(taskId, messageId).orElse(null);
        TaskMsgStatus messageStatus = storedProjection != null ? storedProjection.status() : TaskMsgStatus.ASSIGNED;
        String preferredAttemptId = storedProjection != null ? storedProjection.latestAttemptId() : null;
        int attemptNo = Math.max(1, activeLease.retryCount() + 1);
        String attemptId = preferredAttemptId;
        if ((attemptId == null || attemptId.isBlank()) && matchesRuntimeLease(latestAuditView, activeLease, attemptNo)) {
            attemptId = latestAuditView.attemptId();
        }
        if (attemptId == null || attemptId.isBlank()) {
            attemptId = TaskMessageAttemptSupport.runtimeAttemptId(messageId, attemptNo, activeLease);
        }
        TaskMsgAttempt attempt = new TaskMsgAttempt(attemptId, taskId, messageId, attemptNo);
        attempt.setWorkerId(activeLease.workerId());
        attempt.setWorkerContextId(activeLease.workerContextId());
        attempt.setBatchId(activeLease.batchId());
        if (activeLease.leaseExpireAt() != null) {
            attempt.setLeaseExpireTime(java.time.LocalDateTime.ofInstant(
                    activeLease.leaseExpireAt(),
                    java.time.ZoneId.systemDefault()
            ));
        }
        if (activeLease.leasedAt() != null) {
            attempt.setDispatchTime(java.time.LocalDateTime.ofInstant(
                    activeLease.leasedAt(),
                    java.time.ZoneId.systemDefault()
            ));
        }
        attempt.setStatus(com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus.DISPATCHED);
        if (messageStatus == TaskMsgStatus.RUNNING) {
            attempt.setAckTime(java.time.LocalDateTime.now());
            attempt.setStartTime(java.time.LocalDateTime.now());
            attempt.setStatus(com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus.RUNNING);
        }
        return attempt;
    }

    boolean upsertTaskMessageProjection(String taskId,
                                        TaskDetailStore.TaskMessageProjection projection,
                                        String action) {
        if (projection == null) {
            return false;
        }
        try {
            return taskDetailStore.upsertTaskMessageProjection(taskId, projection);
        } catch (RuntimeException e) {
            logger.warn("Failed to upsert compatibility task message projection for taskId={}, messageId={} during {}",
                    taskId, projection.messageId(), action, e);
            return false;
        }
    }

    void upsertTaskMessageAttemptProjectionBestEffort(String taskId,
                                                      String messageId,
                                                      TaskDetailStore.TaskMessageAttemptProjection projection,
                                                      String action) {
        if (projection == null) {
            return;
        }
        try {
            taskDetailStore.upsertTaskMessageAttemptProjection(taskId, messageId, projection);
        } catch (RuntimeException e) {
            logger.warn("Failed to upsert compatibility attempt projection for taskId={}, messageId={}, attemptId={} during {}; runtime result convergence continues",
                    taskId, messageId, projection.attemptId(), action, e);
        }
    }

    TaskDetailStore.TaskMessageAttemptStats getTaskMessageAttemptStats(String taskId, String messageId) {
        return taskDetailStore.getTaskMessageAttemptStats(taskId, messageId);
    }

    @CompatibilityProjectionOnly
    List<TaskDetailStore.TaskMessageProjection> getTaskMessageProjectionsForAudit(String taskId) {
        return taskDetailStore.getTaskMessageProjections(taskId);
    }

    void persistActiveLeaseProjectionResidue(String taskId) {
        List<ActiveLeaseRecord> activeLeases = activeLeasesLookup.apply(taskId);
        if (activeLeases == null || activeLeases.isEmpty()) {
            return;
        }
        for (ActiveLeaseRecord activeLease : activeLeases) {
            if (activeLease == null || activeLease.messageId() == null || activeLease.messageId().isBlank()) {
                continue;
            }
            String messageId = activeLease.messageId();
            TaskDetailStore.TaskMessageProjection storedProjection = getStoredTaskMessageRecord(taskId, messageId);
            TaskDetailStore.TaskMessageProjection leasedProjection =
                    CompatibilityProjectionSupport.overlayActiveLeaseProjection(
                            storedProjection,
                            activeLease,
                            taskId,
                            messageId
                    );
            if (leasedProjection == null || Objects.equals(storedProjection, leasedProjection)) {
                continue;
            }
            upsertTaskMessageProjection(taskId, leasedProjection, "persist terminal lease residue");
        }
    }

    TaskDetailStore.TaskMessageProjection getStoredTaskMessageRecord(String taskId, String messageId) {
        return taskDetailStore.getTaskMessageProjection(taskId, messageId).orElse(null);
    }

    TaskDetailStore.TaskMessageProjection getVisibleTaskMessageProjection(String taskId, String messageId) {
        Task task = taskLookup.apply(taskId);
        TaskDetailStore.TaskMessageProjection projection = getStoredTaskMessageRecord(taskId, messageId);
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

    private boolean matchesRuntimeLease(TaskDetailStore.TaskMessageAttemptProjection attempt,
                                        ActiveLeaseRecord activeLease,
                                        int attemptNo) {
        if (attempt == null || activeLease == null || attempt.attemptId() == null || attempt.attemptId().isBlank()) {
            return false;
        }
        if (attempt.attemptNo() != attemptNo) {
            return false;
        }
        if (!Objects.equals(attempt.workerId(), activeLease.workerId())) {
            return false;
        }
        if (!Objects.equals(attempt.workerContextId(), activeLease.workerContextId())) {
            return false;
        }
        return Objects.equals(attempt.batchId(), activeLease.batchId());
    }

    private List<TaskMsg> materializeCompatibilityTaskMessages(List<TaskDetailStore.TaskMessageProjection> projections) {
        if (projections == null || projections.isEmpty()) {
            return List.of();
        }
        return projections.stream()
                .map(TaskDetailStore.TaskMessageProjection::toCompatibilityProjection)
                .filter(Objects::nonNull)
                .toList();
    }
}
