package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.util.TraceEventLogger;
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
            if (message == null
                    || message.getLatestAttemptWorkerId() == null
                    || message.getLatestAttemptWorkerId().isBlank()) {
                continue;
            }
            workerIds.add(message.getLatestAttemptWorkerId());
            releaseWorkerContextIfOwnedByTask(
                    task.getTid(),
                    message.getLatestAttemptWorkerId(),
                    message.getLatestAttemptWorkerContextId()
            );
        }

        for (String workerId : workerIds) {
            workerManager.unlockWorker(workerId);
            TraceEventLogger.workerLockReleased(task.getTid(), workerId,
                    "ON_TASK_TERMINAL", "TaskResourceReleaseListener", "task reached terminal");
        }
    }

    public void onTaskMessageAttemptClosed(Task task, TaskMsg taskMsg, TaskMsgAttempt attempt) {
        if (task == null || taskMsg == null || attempt == null || task.getStatus().isFinal()) {
            return;
        }
        String workerId = attempt.getWorkerId();
        if (workerId == null || workerId.isBlank()) {
            return;
        }
        boolean workerStillBusy = taskManager.hasProcessingMessagesForWorker(task.getTid(), workerId);
        if (workerStillBusy) {
            return;
        }

        releaseWorkerContextIfOwnedByTask(task.getTid(), workerId, attempt.getWorkerContextId());
        workerManager.unlockWorker(workerId);
        TraceEventLogger.workerLockReleased(task.getTid(), workerId,
                "ON_TASK_MESSAGE_ATTEMPT_CLOSED", "TaskResourceReleaseListener", "worker has no in-flight messages");

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

        WorkerContext workerContext = workerManager.getWorkerContextById(workerContextId);
        if (workerContext == null || !workerId.equals(workerContext.getWorkerId())) {
            return;
        }
        if (workerContext.getLastBindTaskId() != null && !taskId.equals(workerContext.getLastBindTaskId())) {
            return;
        }
        if (workerContext.getStatus() == WorkerContextStatus.IDLE) {
            return;
        }
        WorkerContextStatus fromStatus = workerContext.getStatus();
        if (workerContext.release()) {
            workerManager.updateWorkerContextById(workerContextId, workerContext);
            TraceEventLogger.workerContextStatusTransition(
                    taskId,
                    workerContext,
                    fromStatus,
                    workerContext.getStatus(),
                    "RELEASE_WORKER_CONTEXT",
                    "TaskResourceReleaseListener",
                    "workerContext released after task/message completion"
            );
            TraceEventLogger.resourceReleased(taskId, workerId, workerContextId, "workerContext released");
            return;
        }

        TraceEventLogger.resourceReleaseFailed(taskId, workerId, workerContextId,
                "workerContext could not transition to IDLE from " + workerContext.getStatus());
        log.warn("WorkerContext {} on worker {} could not be released from status {} for task {}",
                workerContextId, workerId, workerContext.getStatus(), taskId);
    }
}
