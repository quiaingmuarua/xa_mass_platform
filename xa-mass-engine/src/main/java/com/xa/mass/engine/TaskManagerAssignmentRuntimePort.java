package com.xa.mass.engine;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
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

    public TaskManagerAssignmentRuntimePort(TaskManager taskManager) {
        this.taskManager = taskManager;
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
        return taskManager.getTaskMessage(taskId, messageId);
    }

    @Override
    public boolean updateTaskMessage(String taskId, TaskMsg taskMsg) {
        return taskManager.updateTaskMessage(taskId, taskMsg);
    }

    @Override
    public TaskMsgAttempt getLatestTaskMessageAttempt(String taskId, String messageId) {
        return taskManager.getLatestTaskMessageAttempt(taskId, messageId);
    }

    @Override
    public void addTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt) {
        taskManager.addTaskMessageAttempt(taskId, messageId, attempt);
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
}

