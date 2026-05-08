package com.xa.mass.engine;

import com.xa.mass.base.model.Task;
import com.xa.mass.engine.policy.TaskTerminalPolicy;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.TaskWorkRuntime;
import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.api.TaskStorage;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionFinalReason;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionStatus;
import com.xa.mass.engine.strategy.TaskScheduler;

import java.util.List;

/**
 * Test-only compatibility access for bounded TaskMsg / TaskMsgAttempt residue.
 *
 * <p>Mainline engine code should not call through TaskManager for these reads
 * and writes anymore. Tests that still need bounded residue assertions use
 * this explicit helper subtype instead of re-expanding the production surface.</p>
 */
public class ProjectionAwareTaskManager extends TaskManager {

    private final TaskDetailStore taskDetailStore;
    private final TaskCompatibilityProjectionAccess compatibilityProjectionAccess;

    public ProjectionAwareTaskManager(TaskScheduler taskScheduler,
                                      TaskStorage taskStorage,
                                      TaskDetailStore taskDetailStore,
                                      TaskWorkRuntime taskWorkRuntime) {
        super(taskScheduler, taskStorage, taskDetailStore, taskWorkRuntime);
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

    public ProjectionAwareTaskManager(TaskScheduler taskScheduler,
                                      TaskStorage taskStorage,
                                      TaskDetailStore taskDetailStore,
                                      TaskTerminalPolicy taskTerminalPolicy,
                                      TaskWorkRuntime taskWorkRuntime) {
        super(taskScheduler, taskStorage, taskDetailStore, taskTerminalPolicy, taskWorkRuntime);
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
        return taskDetailStore.getTaskMessageProjections(taskId);
    }

    public List<TaskDetailStore.TaskMessageProjection> getTaskMessageRecords(String taskId, int limit) {
        return taskDetailStore.getTaskMessageProjections(taskId, limit);
    }

    public TaskDetailStore.TaskMessageProjection getStoredTaskMessageRecord(String taskId, String messageId) {
        CompatibilityMessageProjection projection =
                compatibilityProjectionAccess.getStoredCompatibilityMessageProjection(taskId, messageId);
        return projection != null ? projection.toStorageProjection() : null;
    }

    public TaskDetailStore.TaskMessageProjection getVisibleTaskMessageProjection(String taskId, String messageId) {
        CompatibilityMessageProjection projection =
                compatibilityProjectionAccess.getVisibleCompatibilityMessageProjection(taskId, messageId);
        return projection != null ? projection.toStorageProjection() : null;
    }

    public ActiveLeaseRecord getActiveLeaseRecord(String taskId, String messageId) {
        return getActiveLease(taskId, messageId).orElse(null);
    }

    public TaskMessageSnapshot getTaskMessageSnapshot(String taskId, int limit) {
        java.util.List<TaskMsg> messages = new java.util.ArrayList<>();
        TaskCompatibilitySnapshotPage snapshot = compatibilityProjectionAccess.visitTaskMessageSnapshot(
                taskId,
                limit,
                (messageId, projectedTaskId, status, latestAttemptId, latestAttemptWorkerId,
                 latestAttemptWorkerContextId, latestAttemptBatchId, retryCount, maxRetryCount,
                 errorMessage, errorCode, finalReason, payloadRef, input, output,
                 assignedTime, createTime, updateTime, startTime, completeTime) ->
                        messages.add(toCompatibilityTaskMessage(
                                messageId,
                                projectedTaskId,
                                status,
                                latestAttemptId,
                                latestAttemptWorkerId,
                                latestAttemptWorkerContextId,
                                latestAttemptBatchId,
                                retryCount,
                                maxRetryCount,
                                errorMessage,
                                errorCode,
                                finalReason,
                                payloadRef,
                                input,
                                output,
                                assignedTime,
                                createTime,
                                updateTime,
                                startTime,
                                completeTime
                        ))
        );
        return new TaskMessageSnapshot(messages, snapshot.limit(), snapshot.truncated());
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

    private TaskMsg toCompatibilityTaskMessage(String messageId,
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
                                               java.util.Map<String, Object> input,
                                               java.util.Map<String, Object> output,
                                               java.time.LocalDateTime assignedTime,
                                               java.time.LocalDateTime createTime,
                                               java.time.LocalDateTime updateTime,
                                               java.time.LocalDateTime startTime,
                                               java.time.LocalDateTime completeTime) {
        TaskMsg taskMsg = payloadRef == null || payloadRef.isBlank()
                ? new TaskMsg(messageId, taskId, input)
                : new TaskMsg(messageId, taskId, input, payloadRef);
        taskMsg.setStatus(status != null ? TaskMsgStatus.valueOf(status) : null);
        taskMsg.applyLatestAttemptProjection(
                latestAttemptId,
                latestAttemptWorkerId,
                latestAttemptWorkerContextId,
                latestAttemptBatchId
        );
        taskMsg.setRetryCount(retryCount);
        taskMsg.setMaxRetryCount(maxRetryCount);
        taskMsg.setErrorMessage(errorMessage);
        taskMsg.setErrorCode(errorCode);
        taskMsg.setFinalReason(finalReason != null
                ? TaskMsgFinalReason.valueOf(finalReason)
                : null);
        taskMsg.setOutput(output);
        taskMsg.setAssignedTime(assignedTime);
        taskMsg.setCreateTime(createTime);
        taskMsg.setUpdateTime(updateTime);
        taskMsg.setStartTime(startTime);
        taskMsg.setCompleteTime(completeTime);
        return taskMsg;
    }
}
