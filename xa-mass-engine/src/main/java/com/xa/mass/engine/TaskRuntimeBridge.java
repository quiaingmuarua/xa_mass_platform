package com.xa.mass.engine;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.engine.runtime.TaskRuntimeEnqueueOptionsResolver;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.ClaimedTaskWork;
import com.xa.mass.runtime.api.ResultApplyOutcome;
import com.xa.mass.runtime.api.TaskWorkClaimOptions;
import com.xa.mass.runtime.api.TaskWorkEnvelope;
import com.xa.mass.runtime.api.TaskWorkResult;
import com.xa.mass.runtime.api.TaskWorkRuntime;
import com.xa.mass.runtime.api.TaskWorkStats;
import com.xa.mass.runtime.api.WorkEnqueueOutcome;
import com.xa.mass.runtime.api.WorkEnqueueStatus;
import com.xa.mass.runtime.api.WorkerClaimTarget;
import com.xa.mass.storage.api.TaskStorage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Engine-internal bridge over {@link TaskWorkRuntime} plus the bounded
 * compatibility projection work needed to enqueue logical task messages.
 */
final class TaskRuntimeBridge {

    private final TaskStorage taskStorage;
    private final TaskWorkRuntime taskWorkRuntime;
    private final TaskRuntimeEnqueueOptionsResolver enqueueOptionsResolver;

    TaskRuntimeBridge(TaskStorage taskStorage,
                      TaskWorkRuntime taskWorkRuntime,
                      TaskRuntimeEnqueueOptionsResolver enqueueOptionsResolver) {
        this.taskStorage = taskStorage;
        this.taskWorkRuntime = taskWorkRuntime;
        this.enqueueOptionsResolver = enqueueOptionsResolver;
    }

    List<Task> getRuntimeDispatchableTasks(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        // Recovery trusts runtime-ready truth first and only resolves the
        // surviving task shells from storage. Missing shells are treated as
        // residue, not as a reason to infer readiness from storage status.
        return taskWorkRuntime.readyTaskIds(limit).stream()
                .map(taskId -> taskStorage.getTask(taskId).orElse(null))
                .filter(task -> task != null)
                .toList();
    }

    int countPendingDispatchableMessages(String taskId) {
        long readyCount = taskWorkRuntime.stats(taskId).readyCount();
        return readyCount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) readyCount;
    }

    boolean hasPendingDispatchableMessages(String taskId) {
        return countPendingDispatchableMessages(taskId) > 0;
    }

    boolean hasProcessingMessagesForWorker(String taskId, String workerId) {
        return taskWorkRuntime.hasActiveLeaseForWorker(taskId, workerId);
    }

    TaskWorkStats getTaskWorkStats(String taskId) {
        return taskWorkRuntime.stats(taskId);
    }

    List<ClaimedTaskWork> claimReady(String taskId,
                                     List<WorkerClaimTarget> claimTargets,
                                     TaskWorkClaimOptions claimOptions) {
        return taskWorkRuntime.claimReady(taskId, claimTargets, claimOptions);
    }

    Optional<ActiveLeaseRecord> getActiveLease(String taskId, String messageId) {
        return taskWorkRuntime.getActiveLease(taskId, messageId);
    }

    List<ActiveLeaseRecord> getActiveLeases(String taskId) {
        return taskWorkRuntime.activeLeases(taskId);
    }

    List<ActiveLeaseRecord> pollExpiredLeases(int limit, Instant now) {
        return taskWorkRuntime.pollExpiredLeases(limit, now);
    }

    void discardTaskRuntime(String taskId) {
        taskWorkRuntime.discardTask(taskId);
    }

    ResultApplyOutcome applyTaskWorkResult(TaskWorkResult result) {
        return taskWorkRuntime.applyResult(result);
    }

    WorkEnqueueOutcome enqueueTaskWork(String taskId, TaskMsg taskMsg) {
        if (taskMsg == null || taskMsg.getStatus() != com.xa.mass.base.enums.taskmsg.TaskMsgStatus.INIT) {
            return null;
        }
        Task task = taskStorage.getTask(taskId).orElse(null);
        TaskWorkEnvelope item = new TaskWorkEnvelope(
                taskId,
                taskMsg.getMessageId(),
                task != null ? TaskSharedConfig.sdkEventCode(task) : null,
                taskMsg.getInput(),
                null,
                taskMsg.getRetryCount(),
                taskMsg.getMaxRetryCount(),
                null,
                null,
                Instant.now()
        );
        return taskWorkRuntime.enqueue(item, enqueueOptionsResolver.resolve(task));
    }

    boolean isTaskWorkEnqueueAccepted(WorkEnqueueOutcome outcome) {
        return outcome == null || outcome.status() == WorkEnqueueStatus.ENQUEUED;
    }

    void shutdown() {
        taskWorkRuntime.shutdown();
    }

    TaskWorkRuntime runtime() {
        return taskWorkRuntime;
    }
}
