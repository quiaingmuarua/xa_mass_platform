package com.xa.mass.engine;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.runtime.api.ClaimedTaskWork;
import com.xa.mass.runtime.api.TaskWorkClaimOptions;
import com.xa.mass.runtime.api.WorkerClaimTarget;

import java.util.List;

/**
 * Assignment-side adapter that keeps runtime listeners off the full TaskManager
 * facade while preserving the current storage/runtime ownership.
 *
 * <p>This remains the concrete {@link TaskAssignmentRuntimePort}
 * implementation used by in-process engine listeners.</p>
 */
public final class TaskManagerAssignmentRuntimePort implements TaskAssignmentRuntimePort {

    private final TaskManager taskManager;
    private final TaskProjectionBridge taskProjectionBridge;

    public TaskManagerAssignmentRuntimePort(TaskManager taskManager) {
        this(taskManager, taskManager.projectionBridge());
    }

    public TaskManagerAssignmentRuntimePort(TaskManager taskManager,
                                            TaskProjectionBridge taskProjectionBridge) {
        this.taskManager = taskManager;
        this.taskProjectionBridge = taskProjectionBridge;
    }

    @Override
    public int countPendingDispatchableMessages(String taskId) {
        return taskManager.countPendingDispatchableMessages(taskId);
    }

    @Override
    public long getTaskMessageLeaseSeconds() {
        return taskManager.getTaskMessageLeaseSeconds();
    }

    @Override
    public TaskMsg getTaskMessage(String taskId, String messageId) {
        return taskProjectionBridge.getTaskMessage(taskId, messageId);
    }

    @Override
    public boolean updateTaskMessage(String taskId, TaskMsg taskMsg) {
        return taskProjectionBridge.updateTaskMessage(taskId, taskMsg);
    }

    @Override
    public TaskMsgAttempt getLatestTaskMessageAttempt(String taskId, String messageId) {
        return taskProjectionBridge.getLatestTaskMessageAttempt(taskId, messageId);
    }

    @Override
    public void addTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt) {
        taskProjectionBridge.addTaskMessageAttempt(taskId, messageId, attempt);
    }

    @Override
    public boolean updateTask(Task task) {
        return taskManager.updateTask(task);
    }

    @Override
    public List<ClaimedTaskWork> claimReady(String taskId,
                                            List<WorkerClaimTarget> claimTargets,
                                            TaskWorkClaimOptions claimOptions) {
        return taskManager.claimReady(taskId, claimTargets, claimOptions);
    }

    @Override
    public boolean compensateDispatchSubmitFailure(Task task,
                                                   List<TaskDispatchBinding> dispatchBindings,
                                                   String detail) {
        return taskManager.compensateDispatchSubmitFailure(task, dispatchBindings, detail);
    }
}

