package com.xa.mass.engine;

import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMessageSnapshot;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.engine.policy.TaskTerminalPolicy;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.TaskWorkRuntime;
import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.api.TaskStorage;
import com.xa.mass.engine.strategy.TaskScheduler;

import java.util.List;
import java.util.Map;

/**
 * Test-only compatibility access for bounded TaskMsg / TaskMsgAttempt residue.
 *
 * <p>Mainline engine code should not call through TaskManager for these reads
 * and writes anymore. Tests that still need bounded residue assertions use
 * this explicit helper subtype instead of re-expanding the production surface.</p>
 */
public class ProjectionAwareTaskManager extends TaskManager {

    private final TaskDetailStore taskDetailStore;

    public ProjectionAwareTaskManager(TaskScheduler taskScheduler,
                                      TaskStorage taskStorage,
                                      TaskDetailStore taskDetailStore,
                                      TaskWorkRuntime taskWorkRuntime) {
        super(taskScheduler, taskStorage, taskDetailStore, taskWorkRuntime);
        this.taskDetailStore = taskDetailStore;
    }

    public ProjectionAwareTaskManager(TaskScheduler taskScheduler,
                                      TaskStorage taskStorage,
                                      TaskDetailStore taskDetailStore,
                                      TaskTerminalPolicy taskTerminalPolicy,
                                      TaskWorkRuntime taskWorkRuntime) {
        super(taskScheduler, taskStorage, taskDetailStore, taskTerminalPolicy, taskWorkRuntime);
        this.taskDetailStore = taskDetailStore;
    }

    public List<TaskDetailStore.TaskMessageProjection> getTaskMessageRecords(String taskId) {
        return taskDetailStore.getTaskMessageProjections(taskId);
    }

    public List<TaskDetailStore.TaskMessageProjection> getTaskMessageRecords(String taskId, int limit) {
        return taskDetailStore.getTaskMessageProjections(taskId, limit);
    }

    public TaskDetailStore.TaskMessageProjection getStoredTaskMessageRecord(String taskId, String messageId) {
        return taskDetailStore.getTaskMessageProjection(taskId, messageId).orElse(null);
    }

    public TaskDetailStore.TaskMessageProjection getVisibleTaskMessageProjection(String taskId, String messageId) {
        Task task = getTask(taskId);
        TaskDetailStore.TaskMessageProjection projection = getStoredTaskMessageRecord(taskId, messageId);
        if (task != null && (task.getStatus() == null || !task.getStatus().isFinal())) {
            projection = CompatibilityProjectionSupport.overlayActiveLeaseProjection(
                    projection,
                    getActiveLease(taskId, messageId).orElse(null),
                    taskId,
                    messageId
            );
        }
        return CompatibilityProjectionSupport.overlayTerminalTaskProjection(task, projection);
    }

    public TaskMessageSnapshot getTaskMessageSnapshot(String taskId, int limit) {
        int boundedLimit = Math.max(0, limit);
        List<TaskDetailStore.TaskMessageProjection> stored = boundedLimit == 0
                ? List.of()
                : taskDetailStore.getTaskMessageProjections(taskId, boundedLimit);
        List<TaskDetailStore.TaskMessageProjection> withActiveLeaseOverlay =
                CompatibilityProjectionSupport.overlayActiveLeaseProjection(
                stored,
                getTaskWorkRuntime().activeLeases(taskId),
                taskId
        );
        List<TaskMsg> projected = materializeCompatibilityTaskMessages(
                CompatibilityProjectionSupport.overlayTerminalTaskProjection(getTask(taskId), withActiveLeaseOverlay)
        );
        boolean truncated = boundedLimit > 0 && taskDetailStore.getTaskMessageStats(taskId).getTotal() > boundedLimit;
        return new TaskMessageSnapshot(projected, boundedLimit, truncated);
    }

    public boolean upsertTaskMessageProjectionRecord(String taskId,
                                                     TaskDetailStore.TaskMessageProjection projection) {
        return taskDetailStore.upsertTaskMessageProjection(
                taskId,
                projection
        );
    }

    public TaskDetailStore.TaskMessageAttemptProjection getLatestTaskMessageAttemptAuditProjection(String taskId,
                                                                                                   String messageId) {
        return taskDetailStore.getLatestTaskMessageAttemptProjection(taskId, messageId).orElse(null);
    }

    public boolean upsertTaskMessageAttemptAuditProjectionRecord(String taskId,
                                                                 String messageId,
                                                                 TaskDetailStore.TaskMessageAttemptProjection projection) {
        return taskDetailStore.upsertTaskMessageAttemptProjection(
                taskId,
                messageId,
                projection
        );
    }

    public TaskDetailStore.TaskMessageAttemptProjection getLatestActiveAttemptProjectionRecord(String taskId,
                                                                                               String messageId) {
        TaskMsgAttempt attempt = getLatestActiveAttemptProjection(taskId, messageId);
        return attempt != null ? TaskDetailStore.TaskMessageAttemptProjection.fromCompatibilityProjection(attempt) : null;
    }

    public TaskMsgAttempt getLatestActiveAttemptProjection(String taskId, String messageId) {
        Task task = getTask(taskId);
        if (task != null && task.getStatus() != null && task.getStatus().isFinal()) {
            return null;
        }
        ActiveLeaseRecord activeLease = getActiveLease(taskId, messageId).orElse(null);
        if (activeLease == null) {
            return null;
        }
        TaskDetailStore.TaskMessageProjection storedProjection = getStoredTaskMessageRecord(taskId, messageId);
        TaskDetailStore.TaskMessageAttemptProjection latestAuditView = getLatestTaskMessageAttemptAuditProjection(taskId, messageId);
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
        attempt.setStatus(TaskMsgAttemptStatus.DISPATCHED);
        if (messageStatus == TaskMsgStatus.RUNNING) {
            attempt.setAckTime(java.time.LocalDateTime.now());
            attempt.setStartTime(java.time.LocalDateTime.now());
            attempt.setStatus(TaskMsgAttemptStatus.RUNNING);
        }
        return attempt;
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
        if (!java.util.Objects.equals(attempt.workerId(), activeLease.workerId())) {
            return false;
        }
        if (!java.util.Objects.equals(attempt.workerContextId(), activeLease.workerContextId())) {
            return false;
        }
        return java.util.Objects.equals(attempt.batchId(), activeLease.batchId());
    }

    private List<TaskMsg> materializeCompatibilityTaskMessages(List<TaskDetailStore.TaskMessageProjection> projections) {
        if (projections == null || projections.isEmpty()) {
            return List.of();
        }
        return projections.stream()
                .map(TaskDetailStore.TaskMessageProjection::toCompatibilityProjection)
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
