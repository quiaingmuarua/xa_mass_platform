package com.xa.mass.engine;

import com.xa.mass.base.model.Task;
import com.xa.mass.engine.policy.TaskTerminalPolicy;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.TaskResultRuntime;
import com.xa.mass.runtime.api.TaskWorkRuntime;
import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.api.TaskStorage;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionFinalReason;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionStatus;

import java.util.List;

/**
 * Test-only bounded access to compatibility projection residue.
 *
 * <p>Mainline engine code should not call through TaskManager for these reads
 * and writes anymore. Tests that still need bounded residue assertions use
 * this explicit helper subtype instead of re-expanding the production surface.</p>
 */
public class ProjectionAwareTaskManager extends TaskManager {

    private final TaskDetailStore taskDetailStore;
    private final TaskCompatibilityProjectionAccess compatibilityProjectionAccess;

    public ProjectionAwareTaskManager(TaskStorage taskStorage,
                                      TaskDetailStore taskDetailStore,
                                      TaskWorkRuntime taskWorkRuntime) {
        super(taskStorage, taskDetailStore, taskWorkRuntime);
        this.taskDetailStore = taskDetailStore;
        this.compatibilityProjectionAccess = new TaskCompatibilityProjectionAccess(
                taskDetailStore,
                this::getTask,
                this::getActiveLease,
                this::getTaskWork,
                this::getActiveLeases,
                this::getTaskWorkStats
        );
    }

    public ProjectionAwareTaskManager(TaskStorage taskStorage,
                                      TaskDetailStore taskDetailStore,
                                      TaskWorkRuntime taskWorkRuntime,
                                      TaskResultRuntime taskResultRuntime) {
        super(taskStorage, taskDetailStore, taskWorkRuntime, taskResultRuntime, null);
        this.taskDetailStore = taskDetailStore;
        this.compatibilityProjectionAccess = new TaskCompatibilityProjectionAccess(
                taskDetailStore,
                this::getTask,
                this::getActiveLease,
                this::getTaskWork,
                this::getActiveLeases,
                this::getTaskWorkStats
        );
    }

    public ProjectionAwareTaskManager(TaskStorage taskStorage,
                                      TaskDetailStore taskDetailStore,
                                      TaskTerminalPolicy taskTerminalPolicy,
                                      TaskWorkRuntime taskWorkRuntime) {
        super(taskStorage, taskDetailStore, taskTerminalPolicy, taskWorkRuntime);
        this.taskDetailStore = taskDetailStore;
        this.compatibilityProjectionAccess = new TaskCompatibilityProjectionAccess(
                taskDetailStore,
                this::getTask,
                this::getActiveLease,
                this::getTaskWork,
                this::getActiveLeases,
                this::getTaskWorkStats
        );
    }

    public List<TaskDetailStore.TaskMessageProjection> getTaskMessageRecords(String taskId) {
        long total = taskDetailStore.getTaskMessageStats(taskId).getTotal();
        if (total <= 0) {
            return List.of();
        }
        return taskDetailStore.getTaskMessageProjections(taskId, Math.toIntExact(total));
    }

    public List<TaskDetailStore.TaskMessageProjection> getTaskMessageRecords(String taskId, int limit) {
        return taskDetailStore.getTaskMessageProjections(taskId, limit);
    }

    public TaskDetailStore.TaskMessageProjection getStoredTaskMessageRecord(String taskId, String messageId) {
        TaskCompatibilityProjectionAccess.MessageProjection projection =
                compatibilityProjectionAccess.getStoredCompatibilityMessageProjection(taskId, messageId);
        return projection != null ? projection.toStorageProjection() : null;
    }

    public TaskDetailStore.TaskMessageProjection getVisibleTaskMessageProjection(String taskId, String messageId) {
        TaskCompatibilityProjectionAccess.MessageProjection projection =
                compatibilityProjectionAccess.getVisibleCompatibilityMessageProjection(taskId, messageId);
        return projection != null ? projection.toStorageProjection() : null;
    }

    public ActiveLeaseRecord getActiveLeaseRecord(String taskId, String messageId) {
        return getActiveLease(taskId, messageId).orElse(null);
    }

    public boolean expireLeasedWork(String taskId, String messageId) {
        return super.expireLeasedWork(taskId, messageId);
    }

    public long getWorkLeaseSeconds() {
        return super.getWorkLeaseSeconds();
    }

    public ProjectionTestSupport.MessageSnapshot getTaskMessageSnapshot(String taskId, int limit) {
        java.util.List<TaskDetailStore.TaskMessageProjection> messages = new java.util.ArrayList<>();
        TaskCompatibilityProjectionAccess.SnapshotPage snapshot = compatibilityProjectionAccess.visitTaskMessageSnapshot(
                taskId,
                limit,
                (messageId, projectedTaskId, status, latestAttemptId, latestAttemptWorkerId,
                 latestAttemptWorkerContextId, latestAttemptBatchId, retryCount, maxRetryCount,
                 errorMessage, errorCode, finalReason, payloadRef, input, output,
                 assignedTime, createTime, updateTime, startTime, completeTime) ->
                        messages.add(new TaskDetailStore.TaskMessageProjection(
                                messageId,
                                projectedTaskId,
                                input,
                                payloadRef,
                                status != null
                                        ? com.xa.mass.storage.api.projection.TaskMessageProjectionStatus.valueOf(status)
                                        : null,
                                assignedTime,
                                createTime,
                                updateTime,
                                startTime,
                                completeTime,
                                retryCount,
                                maxRetryCount,
                                errorMessage,
                                errorCode,
                                finalReason != null
                                        ? com.xa.mass.storage.api.projection.TaskMessageProjectionFinalReason.valueOf(finalReason)
                                        : null,
                                output,
                                latestAttemptId,
                                latestAttemptWorkerId,
                                latestAttemptWorkerContextId,
                                latestAttemptBatchId
                        ))
        );
        return new ProjectionTestSupport.MessageSnapshot(messages, snapshot.limit(), snapshot.truncated());
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
        TaskDetailStore.TaskMessageAttemptProjection[] holder = new TaskDetailStore.TaskMessageAttemptProjection[1];
        boolean found = compatibilityProjectionAccess.visitLatestActiveTaskMessageAttempt(
                taskId,
                messageId,
                (attemptId, projectedTaskId, projectedMessageId, attemptNo, workerId, workerContextId,
                 batchId, status, leaseExpireTime, dispatchTime, ackTime, startTime, finishTime,
                 finalReason, errorMessage, errorCode, output, createTime, updateTime) -> holder[0] =
                        new TaskDetailStore.TaskMessageAttemptProjection(
                                attemptId,
                                projectedTaskId,
                                projectedMessageId,
                                attemptNo,
                                workerId,
                                workerContextId,
                                batchId,
                                status != null
                                        ? TaskMessageAttemptProjectionStatus.valueOf(status)
                                        : TaskMessageAttemptProjectionStatus.DISPATCHED,
                                null,
                                null,
                                null,
                                null
                        )
        );
        if (!found) {
            return null;
        }
        return holder[0];
    }

    public List<TaskDetailStore.TaskMessageAttemptProjection> getVisibleAttemptProjectionRecords(String taskId,
                                                                                                 String messageId) {
        java.util.List<TaskDetailStore.TaskMessageAttemptProjection> attempts = new java.util.ArrayList<>();
        compatibilityProjectionAccess.visitTaskMessageAttemptViews(
                taskId,
                messageId,
                (attemptId, projectedTaskId, projectedMessageId, attemptNo, workerId, workerContextId,
                 batchId, status, leaseExpireTime, dispatchTime, ackTime, startTime, finishTime,
                 finalReason, errorMessage, errorCode, output, createTime, updateTime) -> attempts.add(
                        new TaskDetailStore.TaskMessageAttemptProjection(
                                attemptId,
                                projectedTaskId,
                                projectedMessageId,
                                attemptNo,
                                workerId,
                                workerContextId,
                                batchId,
                                status != null
                                        ? TaskMessageAttemptProjectionStatus.valueOf(status)
                                        : TaskMessageAttemptProjectionStatus.DISPATCHED,
                                finalReason != null
                                        ? TaskMessageAttemptProjectionFinalReason.valueOf(finalReason)
                                        : null,
                                errorMessage,
                                errorCode,
                                output
                        ))
        );
        return List.copyOf(attempts);
    }
}
