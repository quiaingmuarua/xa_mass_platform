package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.User;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.model.TaskResumeResult;
import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskStateValidationResult;
import com.xa.mass.engine.model.TaskTerminalPolicyDecision;
import com.xa.mass.engine.policy.AllMessagesFinalTaskTerminalPolicy;
import com.xa.mass.engine.policy.TaskTerminalPolicy;
import com.xa.mass.engine.storage.TaskStorage;
import com.xa.mass.engine.storage.TaskStorageFactory;
import com.xa.mass.engine.strategy.TaskScheduler;
import com.xa.mass.engine.util.LogUtils;
import com.xa.mass.engine.util.TraceEventLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Owns task CRUD, task-message persistence, and task lifecycle convergence.
 */
public class TaskManager {

    private static final Logger logger = LoggerFactory.getLogger(TaskManager.class);

    private final TaskStorage taskStorage;
    private final TaskScheduler taskScheduler;
    private final TaskTerminalPolicy taskTerminalPolicy;
    private final List<Consumer<Task>> taskReadyListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Task>> taskDispatchListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Task>> taskTerminalListeners = new CopyOnWriteArrayList<>();
    private final List<BiConsumer<Task, TaskMsg>> taskMessageFinalListeners = new CopyOnWriteArrayList<>();

    public TaskManager(TaskScheduler taskScheduler) {
        this(taskScheduler, TaskStorageFactory.createDefaultTaskStorage(), new AllMessagesFinalTaskTerminalPolicy());
    }

    public TaskManager(TaskScheduler taskScheduler, TaskStorage taskStorage) {
        this(taskScheduler, taskStorage, new AllMessagesFinalTaskTerminalPolicy());
    }

    public TaskManager(TaskScheduler taskScheduler, TaskStorage taskStorage, TaskTerminalPolicy taskTerminalPolicy) {
        this.taskScheduler = taskScheduler;
        this.taskStorage = taskStorage;
        this.taskTerminalPolicy = taskTerminalPolicy;
    }

    /**
     * Creates a task plus one persisted {@link TaskMsg} per target input.
     */
    public Task createTask(TaskCreateRequestDto dto) {
        long startTime = System.currentTimeMillis();
        LogUtils.logOperationStart("CREATE_TASK", "TaskManager",
                "taskName", dto.getTaskName(),
                "project", dto.getProject(),
                "routingCode", dto.getRoutingCode());

        try {
            // 1. Create a stable task id.
            String tid = java.util.UUID.randomUUID().toString();
            LogUtils.setTaskId(tid);

            // 2. Map SDK userId into the current shared User model.
            User user = new User();
            user.setName(dto.getUserId());
            LogUtils.setUserId(dto.getUserId());

            // 3. Materialize initial work items from targetList.
            List<String> targets = dto.getTargetList() == null ? Collections.emptyList() : dto.getTargetList();
            if (targets.isEmpty()) {
                throw new IllegalArgumentException("targetList must contain at least one target");
            }
            int initNumber = targets.size();

            // 4. Build the task aggregate.
            Task task = new Task(
                    tid,
                    dto.getTaskName(),
                    dto.getProject(),
                    dto.getRoutingCode(),
                    initNumber,
                    dto.getSharedConfig() != null ? dto.getSharedConfig() : new java.util.HashMap<>(),
                    user
            );
            task.setBatchSize(dto.getBatchSize());
            task.setOpenEnded(dto.isOpenEnded());
            // 5. Persist the task and its task messages.
            taskStorage.saveTask(task);
            for (String target : targets) {
                String msgId = java.util.UUID.randomUUID().toString();
                TaskMsg taskMsg = new TaskMsg(msgId, tid, target);
                taskMsg.setMaxRetryCount(dto.getDefaultMsgMaxRetryCount());
                addTaskMessage(tid, taskMsg);
            }

            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationSuccess("task created: taskId=" + tid + ", initialMessageCount=" + initNumber, duration);

            return task;

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure("TASK_CREATE_ERROR", e.getMessage(), duration);
            logger.error("Failed to create task", e);
            throw e;
        }
    }

    /**
     * Returns a task by id or {@code null} if it does not exist.
     */
    public Task getTask(String taskId) {
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("GET_TASK", "TaskManager", "taskId", taskId);

        Task task = taskStorage.getTask(taskId).orElse(null);

        if (task != null) {
            LogUtils.logOperationSuccess("task loaded", 0);
        } else {
            LogUtils.logOperationFailure("TASK_NOT_FOUND", "task not found", 0);
        }

        return task;
    }

    /**
     * Persists a task update.
     */
    public boolean updateTask(Task task) {
        LogUtils.setTaskId(task.getTid());
        LogUtils.logOperationStart("UPDATE_TASK", "TaskManager", "taskId", task.getTid());

        boolean result = taskStorage.updateTask(task);

        if (result) {
            LogUtils.logOperationSuccess("task updated", 0);
        } else {
            LogUtils.logOperationFailure("TASK_UPDATE_ERROR", "task update failed", 0);
        }

        return result;
    }

    /**
     * Deletes a task if it is still safe to remove.
     */
    public boolean deleteTask(String taskId) {
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("DELETE_TASK", "TaskManager", "taskId", taskId);

        Task task = taskStorage.getTask(taskId).orElse(null);
        if (task == null) {
            LogUtils.logOperationFailure("TASK_DELETE_ERROR", "task not found", 0);
            return false;
        }
        // Only NEW and TERMINAL tasks can be deleted safely.
        // Deleting a READY/RUNNING/PAUSED task would leave in-progress work orphaned.
        com.xa.mass.base.enums.task.TaskStatus status = task.getStatus();
        if (status != com.xa.mass.base.enums.task.TaskStatus.NEW
                && status != com.xa.mass.base.enums.task.TaskStatus.TERMINAL) {
            logger.warn("Refusing to delete non-terminal task: taskId={}, status={}", taskId, status);
            LogUtils.logOperationFailure("TASK_DELETE_REJECTED",
                    "task status " + status + " is not deletable; terminate it first", 0);
            return false;
        }

        boolean result = taskStorage.deleteTask(taskId);

        if (result) {
            LogUtils.logOperationSuccess("task deleted", 0);
        } else {
            LogUtils.logOperationFailure("TASK_DELETE_ERROR", "task deletion failed", 0);
        }

        return result;
    }

    /**
     * Returns all persisted tasks.
     */
    public List<Task> getAllTasks() {
        LogUtils.logOperationStart("GET_ALL_TASKS", "TaskManager");

        List<Task> tasks = taskStorage.getAllTasks();

        LogUtils.logOperationSuccess("loaded all tasks: count=" + tasks.size(), 0);

        return tasks;
    }

    /**
     * Returns tasks currently in the given status.
     */
    public List<Task> getTasksByStatus(TaskStatus status) {
        LogUtils.logOperationStart("GET_TASKS_BY_STATUS", "TaskManager", "status", status.name());

        List<Task> tasks = taskStorage.getTasksByStatus(status.name());

        LogUtils.logOperationSuccess("loaded tasks by status: status=" + status + ", count=" + tasks.size(), 0);

        return tasks;
    }

    /**
     * Returns tasks that the scheduler currently considers schedulable.
     */
    public List<Task> getSchedulableTasks() {
        LogUtils.logOperationStart("GET_SCHEDULABLE_TASKS", "TaskManager");

        List<Task> tasks = taskStorage.getSchedulableTasks();

        LogUtils.logOperationSuccess("loaded schedulable tasks: count=" + tasks.size(), 0);

        return tasks;
    }

    /**
     * Moves a task from review-held state into READY.
     */
    public boolean approveTask(String taskId) {
        long startTime = System.currentTimeMillis();
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("APPROVE_TASK", "TaskManager", "taskId", taskId);

        try {
            Task task = getTask(taskId);
            if (task != null
                    && (task.getStatus() == TaskStatus.NEW || task.getStatus() == TaskStatus.BLOCKED)) {
                TaskStatus fromStatus = task.getStatus();
                boolean result = task.transitionTo(TaskStatus.READY);
                if (result) {
                    TraceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
                            "APPROVE_TASK", "TaskManager", "task approved");
                    taskStorage.updateTask(task);
                    notifyTaskReady(task);
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationSuccess("task approved", duration);
                } else {
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationFailure("TASK_APPROVE_ERROR", "task status transition failed", duration);
                }
                return result;
            } else {
                long duration = System.currentTimeMillis() - startTime;
                LogUtils.logOperationFailure("TASK_APPROVE_ERROR", "task not found or status is not approvable", duration);
                return false;
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure("TASK_APPROVE_ERROR", e.getMessage(), duration);
            logger.error("Failed to approve task", e);
            return false;
        }
    }

    /**
     * Rejects a NEW task into BLOCKED.
     */
    public boolean rejectTask(String taskId) {
        long startTime = System.currentTimeMillis();
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("REJECT_TASK", "TaskManager", "taskId", taskId);

        try {
            Task task = getTask(taskId);
            if (task != null && task.getStatus() == TaskStatus.NEW) {
                TaskStatus fromStatus = task.getStatus();
                boolean result = task.transitionTo(TaskStatus.BLOCKED);
                if (result) {
                    TraceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
                            "REJECT_TASK", "TaskManager", "task rejected");
                    taskStorage.updateTask(task);
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationSuccess("task rejected and moved to BLOCKED", duration);
                } else {
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationFailure("TASK_REJECT_ERROR", "task status transition failed", duration);
                }
                return result;
            } else {
                long duration = System.currentTimeMillis() - startTime;
                LogUtils.logOperationFailure("TASK_REJECT_ERROR", "task not found or status is not rejectable", duration);
                return false;
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure("TASK_REJECT_ERROR", e.getMessage(), duration);
            logger.error("Failed to reject task", e);
            return false;
        }
    }

    /**
     * Blocks a task that already entered scheduling flow.
     */
    public boolean blockTask(String taskId) {
        long startTime = System.currentTimeMillis();
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("BLOCK_TASK", "TaskManager", "taskId", taskId);

        try {
            Task task = getTask(taskId);
            if (task != null
                    && (task.getStatus() == TaskStatus.READY || task.getStatus() == TaskStatus.RUNNING)) {
                TaskStatus fromStatus = task.getStatus();
                boolean result = task.transitionTo(TaskStatus.BLOCKED);
                if (result) {
                    TraceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
                            "BLOCK_TASK", "TaskManager", "task blocked");
                    taskStorage.updateTask(task);
                    taskScheduler.pauseTask(taskId); // stop scheduling while blocked
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationSuccess("task blocked", duration);
                } else {
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationFailure("TASK_BLOCK_ERROR", "task status transition failed", duration);
                }
                return result;
            } else {
                long duration = System.currentTimeMillis() - startTime;
                LogUtils.logOperationFailure("TASK_BLOCK_ERROR", "task not found or status is not blockable", duration);
                return false;
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure("TASK_BLOCK_ERROR", e.getMessage(), duration);
            logger.error("Failed to block task", e);
            return false;
        }
    }

    /**
     * Pauses a READY or RUNNING task.
     */
    public boolean pauseTask(String taskId) {
        long startTime = System.currentTimeMillis();
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("PAUSE_TASK", "TaskManager", "taskId", taskId);

        try {
            Task task = getTask(taskId);
            if (task != null && (task.getStatus() == TaskStatus.READY || task.getStatus() == TaskStatus.RUNNING)) {
                TaskStatus fromStatus = task.getStatus();
                boolean result = task.transitionTo(TaskStatus.PAUSED);
                if (result) {
                    TraceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
                            "PAUSE_TASK", "TaskManager", "task paused");
                    taskStorage.updateTask(task);
                    taskScheduler.pauseTask(taskId);
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationSuccess("task paused", duration);
                } else {
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationFailure("TASK_PAUSE_ERROR", "task status transition failed", duration);
                }
                return result;
            } else {
                long duration = System.currentTimeMillis() - startTime;
                LogUtils.logOperationFailure("TASK_PAUSE_ERROR", "task not found or status is not pausable", duration);
                return false;
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure("TASK_PAUSE_ERROR", e.getMessage(), duration);
            logger.error("Failed to pause task", e);
            return false;
        }
    }

    /**
     * Resumes a paused task.
     *
     * <p>If all task messages already completed while paused, the task is
     * closed to TERMINAL instead of returning to READY.
     */
    public TaskResumeResult resumeTaskDetailed(String taskId) {
        long startTime = System.currentTimeMillis();
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("RESUME_TASK", "TaskManager", "taskId", taskId);

        try {
            Task task = getTask(taskId);
            if (task != null && task.getStatus() == TaskStatus.PAUSED) {
                TaskStorage.TaskMessageStats stats = getTaskMessageStats(taskId);
                TaskTerminalPolicyDecision decision = taskTerminalPolicy.evaluate(task, stats);
                // If all persisted messages already finished while paused, close
                // the task directly instead of putting it back into READY.
                if (decision.getOutcome() == TaskTerminalPolicyDecision.Outcome.FINALIZE_TO_TERMINAL) {
                    // All messages finished while the task was paused. Close directly
                    // instead of re-queueing. Callers can distinguish this
                    // PAUSED->TERMINAL path from the normal PAUSED->READY path.
                    task.setTaskSuccessNumber((int) stats.getSuccess());
                    TaskTerminalReason terminalReason = decision.getTerminalReason();
                    TaskStatus fromStatus = task.getStatus();
                    boolean result = task.transitionTo(TaskStatus.TERMINAL, terminalReason);
                    if (result) {
                        TraceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
                                "RESUME_TASK", "TaskManager", "task already completed while paused");
                        TraceEventLogger.taskTerminalClosed(taskId, fromStatus, terminalReason,
                                "RESUME_TASK", "TaskManager", "task already completed while paused");
                        taskStorage.updateTask(task);
                        notifyTaskTerminal(task);
                        long duration = System.currentTimeMillis() - startTime;
                        LogUtils.logOperationSuccess("task completed while paused and closed to TERMINAL", duration);
                        return TaskResumeResult.completedToTerminal(terminalReason);
                    } else {
                        long duration = System.currentTimeMillis() - startTime;
                        LogUtils.logOperationFailure("TASK_RESUME_ERROR", "task was complete but terminal closure failed", duration);
                    }
                    return TaskResumeResult.rejected();
                }
                TaskStatus fromStatus = task.getStatus();
                boolean result = task.transitionTo(TaskStatus.READY);
                if (result) {
                    TraceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
                            "RESUME_TASK", "TaskManager", "task resumed to ready");
                    taskStorage.updateTask(task);
                    taskScheduler.resumeTask(taskId);
                    notifyTaskReady(task);
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationSuccess("task resumed to READY", duration);
                    return TaskResumeResult.resumedToReady();
                } else {
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationFailure("TASK_RESUME_ERROR", "task status transition failed", duration);
                }
                return TaskResumeResult.rejected();
            } else {
                long duration = System.currentTimeMillis() - startTime;
                LogUtils.logOperationFailure("TASK_RESUME_ERROR", "task not found or status is not resumable", duration);
                return TaskResumeResult.rejected();
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure("TASK_RESUME_ERROR", e.getMessage(), duration);
            logger.error("Failed to resume task", e);
            return TaskResumeResult.rejected();
        }
    }

    public boolean resumeTask(String taskId) {
        return resumeTaskDetailed(taskId).isSuccess();
    }

    /**
     * Manually terminates a non-final task.
     */
    public boolean cancelTask(String taskId) {
        long startTime = System.currentTimeMillis();
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("CANCEL_TASK", "TaskManager", "taskId", taskId);

        try {
            Task task = getTask(taskId);
            if (task != null && !task.getStatus().isFinal()) {
                TaskStatus fromStatus = task.getStatus();
                boolean result = task.transitionTo(TaskStatus.TERMINAL, TaskTerminalReason.MANUAL_CANCELLED);
                if (result) {
                    TraceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
                            "CANCEL_TASK", "TaskManager", "task manually cancelled");
                    TraceEventLogger.taskTerminalClosed(taskId, fromStatus, TaskTerminalReason.MANUAL_CANCELLED,
                            "CANCEL_TASK", "TaskManager", "task manually cancelled");
                    taskStorage.updateTask(task);
                    cancelPendingMessages(taskId); // drain non-final messages to a terminal state
                    taskScheduler.cancelTask(taskId);
                    notifyTaskTerminal(task);
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationSuccess("task cancelled", duration);
                } else {
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationFailure("TASK_CANCEL_ERROR", "task status transition failed", duration);
                }
                return result;
            } else {
                long duration = System.currentTimeMillis() - startTime;
                LogUtils.logOperationFailure("TASK_CANCEL_ERROR", "task not found or status is not cancellable", duration);
                return false;
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure("TASK_CANCEL_ERROR", e.getMessage(), duration);
            logger.error("Failed to cancel task", e);
            return false;
        }
    }

    /**
     * Appends new work items to a READY or RUNNING open-ended task.
     * Only tasks created with {@code openEnded=true} may call this method.
     * Use {@link #sealTask(String)} when no more items will be added.
     *
     * @return number of items added
     */
    public int appendTaskItems(String taskId, java.util.List<java.util.Map<String, Object>> inputs) {
        Task task = getTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        if (!task.isOpenEnded()) {
            throw new IllegalStateException("Task is not open-ended: " + taskId);
        }
        if (!task.getStatus().isActive()) {
            throw new IllegalStateException("Task not active: " + task.getStatus());
        }

        int added = 0;
        for (java.util.Map<String, Object> input : inputs) {
            String msgId = java.util.UUID.randomUUID().toString();
            TaskMsg taskMsg = new TaskMsg(msgId, taskId, input);
            addTaskMessage(taskId, taskMsg);
            added++;
        }
        task.setTaskTargetNumber(task.getTaskTargetNumber() + added);
        task.setTaskEligibleNumber(task.getTaskEligibleNumber() + added);
        updateTask(task);
        notifyTaskDispatchRequested(task);
        logger.info("[appendTaskItems] Added {} items to open-ended task {}", added, taskId);
        return added;
    }

    /**
     * Seals an open-ended task — disables further item appending and allows the terminal
     * policy to close the task once all existing messages reach a final state.
     *
     * @return true if seal was applied; false if task was not found or not open-ended
     */
    public boolean sealTask(String taskId) {
        Task task = getTask(taskId);
        if (task == null || !task.isOpenEnded()) {
            return false;
        }
        task.setOpenEnded(false);
        updateTask(task);
        updateTaskProgress(taskId);
        logger.info("[sealTask] Sealed task {}", taskId);
        return true;
    }

    /**
     * Persists a task message under the owning task.
     */
    public void addTaskMessage(String taskId, TaskMsg taskMsg) {
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("ADD_TASK_MESSAGE", "TaskManager",
                "taskId", taskId,
                "messageId", taskMsg.getMsgId());

        taskStorage.addTaskMessage(taskId, taskMsg);

        LogUtils.logOperationSuccess("task message added", 0);
    }

    /**
     * Returns all persisted task messages for a task.
     */
    public List<TaskMsg> getTaskMessages(String taskId) {
        return taskStorage.getTaskMessages(taskId);
    }

    public TaskMsg getTaskMessage(String taskId, String msgId) {
        return taskStorage.getTaskMessage(taskId, msgId).orElse(null);
    }

    public boolean updateTaskMessage(String taskId, TaskMsg taskMsg) {
        return taskStorage.updateTaskMessage(taskId, taskMsg);
    }

    /**
     * Expires a single in-flight task message and recalculates task convergence.
     */
    public boolean expireTaskMessage(String taskId, String msgId) {
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("EXPIRE_TASK_MESSAGE", "TaskManager",
                "taskId", taskId, "msgId", msgId);

        TaskMsg taskMsg = getTaskMessage(taskId, msgId);
        if (taskMsg == null) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "task message not found", 0);
            return false;
        }
        if (taskMsg.isCompleted()) {
            logger.info("Task message {} of task {} is already in final status {}, skip expiry",
                    msgId, taskId, taskMsg.getStatus());
            return false;
        }
        // Only ASSIGNED/RUNNING messages may expire. INIT/BINDING never left the
        // engine, so they should be cleaned up through task cancellation instead.
        // Only assigned/dispatched messages (ASSIGNED / RUNNING) can be expired.
        // INIT / BINDING never left the engine, so they should not be expired.
        TaskMsgStatus fromStatus = taskMsg.getStatus();
        boolean expired = taskMsg.markAsExpired();
        if (!expired) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR",
                    "task message status " + taskMsg.getStatus() + " cannot expire; only ASSIGNED/RUNNING can expire", 0);
            return false;
        }
        TraceEventLogger.taskMsgStatusTransition(
                taskMsg,
                fromStatus,
                taskMsg.getStatus(),
                "EXPIRE_TASK_MESSAGE",
                "TaskManager",
                "task message expired"
        );
        boolean stored = updateTaskMessage(taskId, taskMsg);
        if (!stored) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "task message persistence failed", 0);
            return false;
        }
        LogUtils.logOperationSuccess("task message expired", 0);
        updateTaskProgress(taskId); // may transition task to TERMINAL if all messages are now done
        return true;
    }

    /**
     * Returns the current persisted task-message aggregate for a task.
     */
    public TaskStorage.TaskMessageStats getTaskMessageStats(String taskId) {
        return taskStorage.getTaskMessageStats(taskId);
    }

    public int countPendingDispatchableMessages(String taskId) {
        return (int) getTaskMessages(taskId).stream()
                .filter(taskMsg -> taskMsg != null && taskMsg.getStatus() == TaskMsgStatus.INIT)
                .count();
    }

    public boolean hasPendingDispatchableMessages(String taskId) {
        return countPendingDispatchableMessages(taskId) > 0;
    }

    /**
     * Recomputes task-level convergence from persisted task messages.
     */
    public void updateTaskProgress(String taskId) {
        resolveTaskStateFromMessages(taskId);
    }

    /**
     * Resolves task state explicitly from the persisted task-message aggregate.
     */
    public TaskStateResolutionResult resolveTaskStateFromMessages(String taskId) {
        Task task = getTask(taskId);
        if (task == null) {
            return TaskStateResolutionResult.taskNotFound();
        }

        TaskStorage.TaskMessageStats stats = getTaskMessageStats(taskId);
        task.setTaskSuccessNumber((int) stats.getSuccess());

        if (task.getStatus().isFinal()) {
            taskStorage.updateTask(task);
            emitTaskProgressSnapshot(task, stats, "ALREADY_FINAL", false,
                    "RESOLVE_TASK_STATE_FROM_MESSAGES", "TaskManager", "task already final");
            return TaskStateResolutionResult.alreadyFinal(
                    task.getStatus(),
                    task.getTerminalReason(),
                    stats.getTotal(),
                    stats.getSuccess(),
                    stats.getFailed()
            );
        }

        TaskTerminalPolicyDecision decision = taskTerminalPolicy.evaluate(task, stats);
        if (decision.getOutcome() != TaskTerminalPolicyDecision.Outcome.FINALIZE_TO_TERMINAL) {
            taskStorage.updateTask(task);
            emitTaskProgressSnapshot(task, stats, "NOT_FINALIZED", false,
                    "RESOLVE_TASK_STATE_FROM_MESSAGES", "TaskManager", "task remains non-final after progress evaluation");
            return TaskStateResolutionResult.notFinalized(
                    task.getStatus(),
                    stats.getTotal(),
                    stats.getSuccess(),
                    stats.getFailed()
            );
        }

        TaskTerminalReason reason = decision.getTerminalReason();
        TaskStatus fromStatus = task.getStatus();
        boolean result = task.transitionTo(TaskStatus.TERMINAL, reason);
        if (result) {
            TraceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
                    "RESOLVE_TASK_STATE_FROM_MESSAGES", "TaskManager", "all persisted messages finalized");
            TraceEventLogger.taskTerminalClosed(taskId, fromStatus, reason,
                    "RESOLVE_TASK_STATE_FROM_MESSAGES", "TaskManager", "all persisted messages finalized");
            taskStorage.updateTask(task);
            emitTaskProgressSnapshot(task, stats, "FINALIZED_TO_TERMINAL", false,
                    "RESOLVE_TASK_STATE_FROM_MESSAGES", "TaskManager", "all persisted messages finalized");
            notifyTaskTerminal(task);
            return TaskStateResolutionResult.finalizedToTerminal(
                    reason,
                    stats.getTotal(),
                    stats.getSuccess(),
                    stats.getFailed()
            );
        }

        taskStorage.updateTask(task);
        emitTaskProgressSnapshot(task, stats, "FINALIZE_REJECTED", true,
                "RESOLVE_TASK_STATE_FROM_MESSAGES", "TaskManager", "task terminal transition was rejected");
        return TaskStateResolutionResult.notFinalized(
                task.getStatus(),
                stats.getTotal(),
                stats.getSuccess(),
                stats.getFailed()
        );
    }

    public TaskStateValidationResult validateTaskState(String taskId) {
        Task task = getTask(taskId);
        if (task == null) {
            return new TaskStateValidationResult(
                    false,
                    false,
                    null,
                    null,
                    0,
                    0,
                    0,
                    0,
                    List.of(TaskStateValidationResult.ViolationCode.TASK_NOT_FOUND)
            );
        }

        TaskStorage.TaskMessageStats stats = getTaskMessageStats(taskId);
        List<TaskStateValidationResult.ViolationCode> violations = new ArrayList<>();

        if (task.getTaskEligibleNumber() < 0) {
            violations.add(TaskStateValidationResult.ViolationCode.NEGATIVE_ELIGIBLE_COUNT);
        }
        if (task.getTaskSuccessNumber() < 0) {
            violations.add(TaskStateValidationResult.ViolationCode.NEGATIVE_SUCCESS_COUNT);
        }
        if (task.getTaskSuccessNumber() > task.getTaskEligibleNumber()) {
            violations.add(TaskStateValidationResult.ViolationCode.SUCCESS_EXCEEDS_ELIGIBLE);
        }
        if (task.getTaskNonSuccessNumber() != task.getTaskEligibleNumber() - task.getTaskSuccessNumber()) {
            violations.add(TaskStateValidationResult.ViolationCode.NON_SUCCESS_COUNT_MISMATCH);
        }

        boolean finalStatus = task.getStatus() != null && task.getStatus().isFinal();
        boolean hasTerminalReason = task.getTerminalReason() != null;
        if (finalStatus && !hasTerminalReason) {
            violations.add(TaskStateValidationResult.ViolationCode.TERMINAL_REASON_MISSING);
        }
        if (!finalStatus && hasTerminalReason) {
            violations.add(TaskStateValidationResult.ViolationCode.TERMINAL_REASON_PRESENT_ON_NON_TERMINAL);
        }

        if (finalStatus && hasTerminalReason) {
            switch (task.getTerminalReason()) {
                case ALL_MESSAGES_SUCCEEDED -> {
                    if (!(stats.getTotal() > 0 && stats.getSuccess() == stats.getTotal() && stats.getFailed() == 0 && stats.getExpired() == 0 && stats.getProcessing() == 0)) {
                        violations.add(TaskStateValidationResult.ViolationCode.TERMINAL_REASON_MISMATCH_ALL_SUCCEEDED);
                    }
                }
                case ALL_MESSAGES_FAILED -> {
                    if (!(stats.getTotal() > 0 && stats.getFailed() + stats.getExpired() == stats.getTotal() && stats.getSuccess() == 0 && stats.getProcessing() == 0)) {
                        violations.add(TaskStateValidationResult.ViolationCode.TERMINAL_REASON_MISMATCH_ALL_FAILED);
                    }
                }
                case MIXED_MESSAGE_RESULTS -> {
                    boolean mixed = stats.getTotal() > 0
                            && stats.getSuccess() > 0
                            && stats.getFailed() + stats.getExpired() > 0
                            && stats.getSuccess() + stats.getFailed() + stats.getExpired() == stats.getTotal()
                            && stats.getProcessing() == 0;
                    if (!mixed) {
                        violations.add(TaskStateValidationResult.ViolationCode.TERMINAL_REASON_MISMATCH_MIXED_RESULTS);
                    }
                }
                case MANUAL_CANCELLED -> {
                    // Manual cancel is allowed regardless of message finality snapshot.
                }
            }
        }

        boolean needsResolution = !finalStatus
                && taskTerminalPolicy.evaluate(task, stats).getOutcome() == TaskTerminalPolicyDecision.Outcome.FINALIZE_TO_TERMINAL;
        return new TaskStateValidationResult(
                violations.isEmpty(),
                needsResolution,
                task.getStatus(),
                task.getTerminalReason(),
                stats.getTotal(),
                stats.getSuccess(),
                stats.getFailed(),
                stats.getProcessing(),
                List.copyOf(violations)
        );
    }

    private void emitTaskProgressSnapshot(Task task,
                                          TaskStorage.TaskMessageStats stats,
                                          String resolutionOutcome,
                                          boolean needsTerminalClosure,
                                          String trigger,
                                          String source,
                                          String reason) {
        TraceEventLogger.taskProgressSnapshot(
                task,
                stats,
                resolutionOutcome,
                needsTerminalClosure,
                trigger,
                source,
                reason
        );
    }

    public TaskScheduler getScheduler() {
        return this.taskScheduler;
    }

    public void addTaskReadyListener(Consumer<Task> listener) {
        if (listener != null) {
            taskReadyListeners.add(listener);
        }
    }

    public void addTaskDispatchListener(Consumer<Task> listener) {
        if (listener != null) {
            taskDispatchListeners.add(listener);
        }
    }

    public void addTaskTerminalListener(Consumer<Task> listener) {
        if (listener != null) {
            taskTerminalListeners.add(listener);
        }
    }

    public void addTaskMessageFinalListener(BiConsumer<Task, TaskMsg> listener) {
        if (listener != null) {
            taskMessageFinalListeners.add(listener);
        }
    }

    private void notifyTaskReady(Task task) {
        for (Consumer<Task> listener : taskReadyListeners) {
            try {
                listener.accept(task);
            } catch (Exception e) {
                logger.error("READY listener execution failed for task {}", task.getTid(), e);
            }
        }
    }

    private void notifyTaskTerminal(Task task) {
        for (Consumer<Task> listener : taskTerminalListeners) {
            try {
                listener.accept(task);
            } catch (Exception e) {
                logger.error("TERMINAL listener execution failed for task {}", task.getTid(), e);
            }
        }
    }

    private void notifyTaskDispatchRequested(Task task) {
        for (Consumer<Task> listener : taskDispatchListeners) {
            try {
                listener.accept(task);
            } catch (Exception e) {
                logger.error("Dispatch listener execution failed for task {}", task.getTid(), e);
            }
        }
    }

    private void notifyTaskMessageFinal(Task task, TaskMsg taskMsg) {
        for (BiConsumer<Task, TaskMsg> listener : taskMessageFinalListeners) {
            try {
                listener.accept(task, taskMsg);
            } catch (Exception e) {
                logger.error("Task message final listener failed for task {}, msg {}", task.getTid(), taskMsg.getMsgId(), e);
            }
        }
    }

    public boolean handleTaskMessageResult(String taskId, String msgId, boolean success, String detail) {
        Task task = getTask(taskId);
        if (task == null) {
            logger.warn("Cannot handle task message result because task {} was not found", taskId);
            return false;
        }

        TaskMsg taskMsg = getTaskMessage(taskId, msgId);
        if (taskMsg == null) {
            logger.warn("Cannot handle task message result because msg {} was not found in task {}", msgId, taskId);
            return false;
        }

        if (taskMsg.isCompleted()) {
            TraceEventLogger.callbackIgnoredDuplicate(taskMsg,
                    "task message already final in status " + taskMsg.getStatus());
            logger.info("Task message {} of task {} is already in final status {}, skipping duplicate result",
                    msgId, taskId, taskMsg.getStatus());
            updateTaskProgress(taskId);
            return true;
        }

        if (task.getStatus().isFinal()) {
            TraceEventLogger.callbackIgnoredLate(taskMsg,
                    "task already terminal in status " + task.getStatus());
            logger.info("Ignoring late result for terminal task {}, msg {} still in status {}",
                    taskId, msgId, taskMsg.getStatus());
            return true;
        }

        TraceEventLogger.callbackAccepted(taskMsg, success ? "success callback received" : "failure callback received");
        if (!advanceTaskMsgForCompletion(taskMsg, success)) {
            logger.warn("Cannot advance task message {} from status {} for completion",
                    msgId, taskMsg.getStatus());
            return false;
        }

        TaskMsgStatus beforeFinalStatus = taskMsg.getStatus();
        boolean statusUpdated = success ? taskMsg.markAsSuccess(detail) : taskMsg.markAsFailed(detail);
        if (!statusUpdated) {
            logger.warn("Failed to mark task message {} as {}", msgId, success ? "SUCCESS" : "FAILED");
            return false;
        }
        TraceEventLogger.taskMsgStatusTransition(
                taskMsg,
                beforeFinalStatus,
                taskMsg.getStatus(),
                "HANDLE_TASK_MESSAGE_RESULT",
                "TaskManager",
                success ? "task message marked success" : "task message marked failure"
        );

        // Before persisting a terminal failure, attempt retry. resetForRetry()
        // moves the message back to INIT so the terminal policy does not see a
        // final failure yet. The task stays RUNNING and re-dispatch is triggered
        // through notifyTaskMessageFinal(updatedTask, taskMsg).
        if (!success && taskMsg.resetForRetry()) {
            TraceEventLogger.taskMsgRetryReset(taskMsg,
                    "HANDLE_TASK_MESSAGE_RESULT", "TaskManager", "retry budget allows re-dispatch");
            logger.info("Task message {} of task {} reset for retry (attempt {})", msgId, taskId, taskMsg.getRetryCount());
            boolean stored = updateTaskMessage(taskId, taskMsg);
            if (!stored) {
                logger.warn("Failed to persist retry state for task message {} in task {}", msgId, taskId);
                return false;
            }
            updateTaskProgress(taskId);
            Task updatedTask = getTask(taskId);
            if (updatedTask != null && !updatedTask.getStatus().isFinal()) {
                notifyTaskMessageFinal(updatedTask, taskMsg);
            }
            return true;
        }

        boolean stored = updateTaskMessage(taskId, taskMsg);
        if (!stored) {
            logger.warn("Failed to persist task message {} for task {}", msgId, taskId);
            return false;
        }

        if (success) {
            taskScheduler.handleTaskMsgCompletion(taskMsg);
        } else {
            taskScheduler.handleTaskMsgFailure(taskMsg, detail);
        }

        updateTaskProgress(taskId);
        Task updatedTask = getTask(taskId);
        if (updatedTask != null && !updatedTask.getStatus().isFinal()) {
            notifyTaskMessageFinal(updatedTask, taskMsg);
        }
        return true;
    }
    private boolean advanceTaskMsgForCompletion(TaskMsg taskMsg, boolean success) {
        TaskMsgStatus status = taskMsg.getStatus();
        if (status == null) {
            return false;
        }
        if (status.isFinal()) {
            return true;
        }
        // Normalize message history so completion always passes through the
        // lifecycle stages that runtime metrics and trace expect.
        // Always normalize INIT -> BINDING before final completion.
        if (status == TaskMsgStatus.INIT) {
            TaskMsgStatus fromStatus = status;
            if (!taskMsg.transitionTo(TaskMsgStatus.BINDING)) {
                return false;
            }
            TraceEventLogger.taskMsgStatusTransition(
                    taskMsg,
                    fromStatus,
                    taskMsg.getStatus(),
                    "ADVANCE_TASK_MSG_FOR_COMPLETION",
                    "TaskManager",
                    "normalized completion path"
            );
        }
        status = taskMsg.getStatus();
        // Always normalize BINDING -> ASSIGNED regardless of success/failure so the
        // final markAsSuccess/markAsFailed is always called from RUNNING state.
        // (BINDING -> FAILED is technically allowed by the state machine but skips
        // the RUNNING stage that callers expect to see in logs/metrics.)
        if (status == TaskMsgStatus.BINDING) {
            TaskMsgStatus fromStatus = status;
            if (!taskMsg.markAsAssigned()) {
                return false;
            }
            TraceEventLogger.taskMsgStatusTransition(
                    taskMsg,
                    fromStatus,
                    taskMsg.getStatus(),
                    "ADVANCE_TASK_MSG_FOR_COMPLETION",
                    "TaskManager",
                    "normalized completion path"
            );
            status = taskMsg.getStatus();
        }
        // Always normalize ASSIGNED -> RUNNING before the caller applies the terminal mark.
        if (status == TaskMsgStatus.ASSIGNED) {
            if (!taskMsg.markAsRunning()) {
                return false;
            }
            TraceEventLogger.taskMsgStatusTransition(
                    taskMsg,
                    status,
                    taskMsg.getStatus(),
                    "ADVANCE_TASK_MSG_FOR_COMPLETION",
                    "TaskManager",
                    "normalized completion path"
            );
            return true;
        }
        return true;
    }

    /**
     * Forces all non-final task messages into a terminal state after cancelTask.
     *
     * <ul>
     *   <li>INIT / BINDING -> FAILED because the engine never dispatched them</li>
     *   <li>ASSIGNED / RUNNING -> EXPIRED because dispatch began but was aborted</li>
     * </ul>
     */
    private void cancelPendingMessages(String taskId) {
        List<TaskMsg> messages = getTaskMessages(taskId);
        for (TaskMsg msg : messages) {
            if (msg.isCompleted()) continue;

            TaskMsgStatus s = msg.getStatus();
            boolean updated = false;
            // Messages that never left the engine are finalized as FAILED
            // during manual task cancellation.
            if (s == TaskMsgStatus.INIT) {
                // INIT must move through BINDING before it can become FAILED.
                if (msg.transitionTo(TaskMsgStatus.BINDING)) {
                    TraceEventLogger.taskMsgStatusTransition(
                            msg,
                            TaskMsgStatus.INIT,
                            TaskMsgStatus.BINDING,
                            "CANCEL_PENDING_MESSAGES",
                            "TaskManager",
                            "task cancelled before dispatch"
                    );
                }
                updated = msg.markAsFailed("task cancelled");
                if (updated) {
                    TraceEventLogger.taskMsgStatusTransition(
                            msg,
                            TaskMsgStatus.BINDING,
                            msg.getStatus(),
                            "CANCEL_PENDING_MESSAGES",
                            "TaskManager",
                            "task cancelled before dispatch"
                    );
                }
            } else if (s == TaskMsgStatus.BINDING) {
                updated = msg.markAsFailed("task cancelled");
                if (updated) {
                    TraceEventLogger.taskMsgStatusTransition(
                            msg,
                            TaskMsgStatus.BINDING,
                            msg.getStatus(),
                            "CANCEL_PENDING_MESSAGES",
                            "TaskManager",
                            "task cancelled during binding"
                    );
                }
            } else if (s == TaskMsgStatus.ASSIGNED || s == TaskMsgStatus.RUNNING) {
                updated = msg.markAsExpired();
                if (updated) {
                    TraceEventLogger.taskMsgStatusTransition(
                            msg,
                            s,
                            msg.getStatus(),
                            "CANCEL_PENDING_MESSAGES",
                            "TaskManager",
                            "task cancelled after assignment"
                    );
                }
            }
            if (updated) {
                updateTaskMessage(taskId, msg);
            }
        }
    }
}
