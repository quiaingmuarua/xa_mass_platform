package com.xa.mass.engine;

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

    public List<TaskMsg> getTaskMessages(String taskId) {
        return taskDetailStore.getTaskMessages(taskId);
    }

    public List<TaskMsg> getTaskMessages(String taskId, int limit) {
        return taskDetailStore.getTaskMessages(taskId, limit);
    }

    public TaskMsg getStoredTaskMessageProjection(String taskId, String messageId) {
        return taskDetailStore.getTaskMessage(taskId, messageId).orElse(null);
    }

    public TaskMsg getTaskMessageProjection(String taskId, String messageId) {
        Task task = getTask(taskId);
        TaskMsg projection = getStoredTaskMessageProjection(taskId, messageId);
        if (task != null && (task.getStatus() == null || !task.getStatus().isFinal())) {
            projection = CompatibilityProjectionSupport.overlayActiveLeaseView(
                    projection,
                    getActiveLease(taskId, messageId).orElse(null),
                    taskId,
                    messageId
            );
        }
        return CompatibilityProjectionSupport.overlayTerminalTaskView(task, projection);
    }

    public TaskMessageSnapshot getTaskMessageSnapshot(String taskId, int limit) {
        int boundedLimit = Math.max(0, limit);
        List<TaskMsg> stored = boundedLimit == 0 ? List.of() : taskDetailStore.getTaskMessages(taskId, boundedLimit);
        List<TaskMsg> withActiveLeaseOverlay = CompatibilityProjectionSupport.overlayActiveLeaseView(
                stored,
                getTaskWorkRuntime().activeLeases(taskId),
                taskId
        );
        List<TaskMsg> projected = CompatibilityProjectionSupport.overlayTerminalTaskView(getTask(taskId), withActiveLeaseOverlay);
        boolean truncated = boundedLimit > 0 && taskDetailStore.countTaskMessages(taskId) > boundedLimit;
        return new TaskMessageSnapshot(projected, boundedLimit, truncated);
    }

    public boolean updateTaskMessageProjection(String taskId, TaskMsg taskMsg) {
        return taskDetailStore.updateTaskMessage(taskId, taskMsg);
    }

    public void addTaskMessageAttemptAuditProjection(String taskId, String messageId, TaskMsgAttempt attempt) {
        taskDetailStore.addTaskMessageAttempt(taskId, messageId, attempt);
    }

    public TaskMsgAttempt getLatestTaskMessageAttemptAuditView(String taskId, String messageId) {
        return taskDetailStore.getLatestTaskMessageAttempt(taskId, messageId).orElse(null);
    }

    public boolean updateTaskMessageAttemptAuditProjection(String taskId, String messageId, TaskMsgAttempt attempt) {
        return taskDetailStore.updateTaskMessageAttempt(taskId, messageId, attempt);
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
        TaskMsg storedProjection = getStoredTaskMessageProjection(taskId, messageId);
        TaskMsgAttempt latestAuditView = getLatestTaskMessageAttemptAuditView(taskId, messageId);
        TaskMsgStatus messageStatus = storedProjection != null ? storedProjection.getStatus() : TaskMsgStatus.ASSIGNED;
        String preferredAttemptId = storedProjection != null ? storedProjection.latestAttemptId() : null;
        return TaskMessageAttemptSupport.runtimeActiveProjection(
                taskId,
                messageId,
                messageStatus,
                preferredAttemptId,
                activeLease,
                latestAuditView
        );
    }
}
