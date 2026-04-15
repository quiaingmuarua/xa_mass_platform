package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Releases runtime-only resource occupancy when a task reaches TERMINAL.
 */
public class TaskResourceReleaseListener {

    private static final Logger log = LoggerFactory.getLogger(TaskResourceReleaseListener.class);

    private final TaskManager taskManager;
    private final WorkerManager workerManager;
    private final Consumer<Task> dispatchRequester;

    public TaskResourceReleaseListener(TaskManager taskManager,
                                       WorkerManager workerManager,
                                       Consumer<Task> dispatchRequester) {
        this.taskManager = taskManager;
        this.workerManager = workerManager;
        this.dispatchRequester = dispatchRequester;
    }

    public void onTaskTerminal(Task task) {
        if (task == null) {
            return;
        }

        List<TaskMsg> messages = taskManager.getTaskMessages(task.getTid());
        Set<String> workerIds = new LinkedHashSet<>();

        for (TaskMsg message : messages) {
            if (message == null || message.getWorkerId() == null || message.getWorkerId().isBlank()) {
                continue;
            }
            workerIds.add(message.getWorkerId());
            releaseWorkerContextIfOwnedByTask(task.getTid(), message.getWorkerId(), message.getWorkerContextId());
        }

        for (String workerId : workerIds) {
            workerManager.unlockWorker(workerId);
        }
    }

    public void onTaskMessageFinal(Task task, TaskMsg taskMsg) {
        if (task == null || taskMsg == null || task.getStatus().isFinal()) {
            return;
        }
        String workerId = taskMsg.getWorkerId();
        if (workerId == null || workerId.isBlank()) {
            return;
        }
        boolean workerStillBusy = taskManager.getTaskMessages(task.getTid()).stream()
                .filter(message -> message != null)
                .filter(message -> workerId.equals(message.getWorkerId()))
                .anyMatch(TaskMsg::isProcessing);
        if (workerStillBusy) {
            return;
        }

        releaseWorkerContextIfOwnedByTask(task.getTid(), workerId, taskMsg.getWorkerContextId());
        workerManager.unlockWorker(workerId);

        if (dispatchRequester != null
                && task.getStatus() == TaskStatus.RUNNING
                && taskManager.hasPendingDispatchableMessages(task.getTid())) {
            dispatchRequester.accept(task);
        }
    }

    private void releaseWorkerContextIfOwnedByTask(String taskId, String workerId, String workerContextId) {
        if (workerContextId == null || workerContextId.isBlank()) {
            return;
        }

        WorkerContext workerContext = workerManager.getWorkerContext(workerId);
        if (workerContext == null || !workerContextId.equals(workerContext.getWorkerContextId())) {
            return;
        }
        if (workerContext.getLastBindTaskId() != null && !taskId.equals(workerContext.getLastBindTaskId())) {
            return;
        }
        if (workerContext.getStatus() == WorkerContextStatus.IDLE) {
            return;
        }
        if (workerContext.release()) {
            workerManager.updateWorkerContext(workerId, workerContext);
            return;
        }

        log.warn("WorkerContext {} on worker {} could not be released from status {} for task {}",
                workerContextId, workerId, workerContext.getStatus(), taskId);
    }
}
