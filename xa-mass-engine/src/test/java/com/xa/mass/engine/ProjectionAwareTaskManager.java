package com.xa.mass.engine;

import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMessageSnapshot;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.engine.policy.TaskTerminalPolicy;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.TaskWorkRuntime;
import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.api.TaskStorage;
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
                this::getActiveLeases
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
                this::getActiveLeases
        );
    }

    public List<TaskDetailStore.TaskMessageProjection> getTaskMessageRecords(String taskId) {
        return taskDetailStore.getTaskMessageProjections(taskId);
    }

    public List<TaskDetailStore.TaskMessageProjection> getTaskMessageRecords(String taskId, int limit) {
        return taskDetailStore.getTaskMessageProjections(taskId, limit);
    }

    public TaskDetailStore.TaskMessageProjection getStoredTaskMessageRecord(String taskId, String messageId) {
        return compatibilityProjectionAccess.getStoredTaskMessageRecord(taskId, messageId);
    }

    public TaskDetailStore.TaskMessageProjection getVisibleTaskMessageProjection(String taskId, String messageId) {
        return compatibilityProjectionAccess.getVisibleTaskMessageProjection(taskId, messageId);
    }

    public ActiveLeaseRecord getActiveLeaseRecord(String taskId, String messageId) {
        return getActiveLease(taskId, messageId).orElse(null);
    }

    public TaskMessageSnapshot getTaskMessageSnapshot(String taskId, int limit) {
        return compatibilityProjectionAccess.getTaskMessageSnapshot(taskId, limit);
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
        TaskMsgAttempt attempt = compatibilityProjectionAccess.getLatestActiveTaskMessageAttemptView(taskId, messageId);
        if (attempt == null) {
            return null;
        }
        TaskDetailStore.TaskMessageAttemptProjection latestAuditView = getLatestTaskMessageAttemptAuditProjection(taskId, messageId);
        TaskMsgAttemptStatus attemptStatus = attempt.getStatus() != null
                ? attempt.getStatus()
                : TaskMsgAttemptStatus.DISPATCHED;
        return new TaskDetailStore.TaskMessageAttemptProjection(
                attempt.getAttemptId(),
                attempt.getTaskId(),
                attempt.getMessageId(),
                attempt.getAttemptNo(),
                attempt.getWorkerId(),
                attempt.getWorkerContextId(),
                attempt.getBatchId(),
                attemptStatus,
                null,
                null,
                null,
                null
        );
    }
}
