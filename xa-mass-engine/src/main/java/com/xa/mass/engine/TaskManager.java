package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskIntakeStatus;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.ProjectRef;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.UserRef;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.model.TaskResumeResult;
import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskStateValidationResult;
import com.xa.mass.engine.policy.AllMessagesFinalTaskTerminalPolicy;
import com.xa.mass.engine.policy.TaskTerminalPolicy;
import com.xa.mass.engine.storage.TaskStorage;
import com.xa.mass.engine.storage.TaskStorageFactory;
import com.xa.mass.engine.strategy.TaskScheduler;
import com.xa.mass.engine.util.LogUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Facade over task CRUD, task-message persistence, lifecycle convergence, and result handling.
 */
public class TaskManager {

    private static final Logger logger = LoggerFactory.getLogger(TaskManager.class);

    private final TaskStorage taskStorage;
    private final TaskScheduler taskScheduler;
    private final TaskTerminalPolicy taskTerminalPolicy;
    private final TaskEventPublisher eventPublisher;
    private final TaskStateResolver stateResolver;
    private final TaskStateValidator stateValidator;
    private final TaskLifecycleService lifecycleService;
    private final TaskResultService resultService;
    private final Map<String, ReentrantLock> taskLocks = new ConcurrentHashMap<>();

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
        this.eventPublisher = new TaskEventPublisher();
        this.stateResolver = new TaskStateResolver(this);
        this.stateValidator = new TaskStateValidator(this);
        this.lifecycleService = new TaskLifecycleService(this, stateResolver);
        this.resultService = new TaskResultService(this, stateResolver);
    }

    /**
     * Creates a task plus one persisted {@link TaskMsg} per work-item input.
     */
    public Task createTask(TaskCreateRequestDto dto) {
        validateCreateRequest(dto);
        long startTime = System.currentTimeMillis();
        LogUtils.logOperationStart("CREATE_TASK", "TaskManager",
                "taskName", dto.getTaskName(),
                "project", dto.getProject(),
                "routingCode", TaskSharedConfig.stringValue(dto.getSharedConfig(), TaskSharedConfig.ROUTING_CODE));

        try {
            String tid = java.util.UUID.randomUUID().toString();
            LogUtils.setTaskId(tid);

            UserRef user = UserRef.of(dto.getUserId());
            LogUtils.setUserId(dto.getUserId());

            List<Map<String, Object>> inputs = dto.getInputs() == null ? List.of() : dto.getInputs();
            if (inputs.isEmpty()) {
                throw new IllegalArgumentException("inputs must contain at least one work item");
            }
            int initNumber = inputs.size();

            Task task = new Task(
                    tid,
                    dto.getTaskName(),
                    dto.getProject(),
                    initNumber,
                    dto.getSharedConfig() != null ? dto.getSharedConfig() : new java.util.HashMap<>(),
                    user
            );
            task.setBatchSize(dto.getBatchSize());
            // intakeStatus is the runtime truth; openEnded remains a compatibility projection.
            task.setIntakeStatus(dto.isOpenEnded() ? TaskIntakeStatus.OPEN : TaskIntakeStatus.SEALED);
            task.setMaxRuntimeSeconds(dto.getMaxRuntimeSeconds());
            taskStorage.saveTask(task);
            for (Map<String, Object> input : inputs) {
                String msgId = java.util.UUID.randomUUID().toString();
                TaskMsg taskMsg = new TaskMsg(msgId, tid, input);
                taskMsg.setMaxRetryCount(dto.getDefaultMsgMaxRetryCount());
                addTaskMessage(tid, taskMsg);
            }

            eventPublisher.publishTaskCreated(task);
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
        return withTaskLock(taskId, () -> lifecycleService.deleteTask(taskId));
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

        List<Task> tasks = taskStorage.getTasksByStatus(status);

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

    public boolean approveTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.approveTask(taskId));
    }

    public boolean rejectTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.rejectTask(taskId));
    }

    public boolean blockTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.blockTask(taskId));
    }

    public boolean pauseTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.pauseTask(taskId));
    }

    public TaskResumeResult resumeTaskDetailed(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.resumeTaskDetailed(taskId));
    }

    public boolean resumeTask(String taskId) {
        return resumeTaskDetailed(taskId).isSuccess();
    }

    /**
     * Manually terminates a non-final task (operator/user-initiated cancellation).
     */
    public boolean cancelTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.cancelTask(taskId));
    }

    /**
     * Policy-driven task termination (e.g. max-runtime exceeded, success-rate reached).
     */
    public boolean terminateTask(String taskId, TaskTerminalReason reason) {
        return withTaskLock(taskId, () -> lifecycleService.terminateTask(taskId, reason));
    }

    /**
     * Appends new work items to a READY or RUNNING open-ended task.
     */
    public int appendTaskItems(String taskId, List<java.util.Map<String, Object>> inputs) {
        return withTaskLock(taskId, () -> lifecycleService.appendTaskItems(taskId, inputs));
    }

    /**
     * Seals an open-ended task.
     */
    public boolean sealTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.sealTask(taskId));
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

    public void addTaskMessageAttempt(String taskId, String msgId, TaskMsgAttempt attempt) {
        taskStorage.addTaskMessageAttempt(taskId, msgId, attempt);
    }

    public List<TaskMsgAttempt> getTaskMessageAttempts(String taskId, String msgId) {
        return taskStorage.getTaskMessageAttempts(taskId, msgId);
    }

    public TaskMsgAttempt getLatestTaskMessageAttempt(String taskId, String msgId) {
        return taskStorage.getLatestTaskMessageAttempt(taskId, msgId).orElse(null);
    }

    public TaskMsgAttempt getLatestActiveTaskMessageAttempt(String taskId, String msgId) {
        return taskStorage.getLatestActiveTaskMessageAttempt(taskId, msgId).orElse(null);
    }

    public boolean updateTaskMessageAttempt(String taskId, String msgId, TaskMsgAttempt attempt) {
        return taskStorage.updateTaskMessageAttempt(taskId, msgId, attempt);
    }

    /**
     * Expires a single in-flight task message and recalculates task convergence.
     */
    public boolean expireTaskMessage(String taskId, String msgId) {
        return withTaskLock(taskId, () -> resultService.expireTaskMessage(taskId, msgId));
    }

    /**
     * Returns the current persisted task-message aggregate for a task.
     */
    public TaskStorage.TaskMessageStats getTaskMessageStats(String taskId) {
        return taskStorage.getTaskMessageStats(taskId);
    }

    public int countPendingDispatchableMessages(String taskId) {
        return taskStorage.countPendingDispatchableMessages(taskId);
    }

    public boolean hasPendingDispatchableMessages(String taskId) {
        return countPendingDispatchableMessages(taskId) > 0;
    }

    /**
     * Recomputes task-level convergence from persisted task messages.
     */
    public void updateTaskProgress(String taskId) {
        withTaskLock(taskId, () -> stateResolver.updateTaskProgress(taskId));
    }

    /**
     * Resolves task state explicitly from the persisted task-message aggregate.
     */
    public TaskStateResolutionResult resolveTaskStateFromMessages(String taskId) {
        return withTaskLock(taskId, () -> stateResolver.resolveTaskStateFromMessages(taskId));
    }

    public TaskStateValidationResult validateTaskState(String taskId) {
        return withTaskLock(taskId, () -> stateValidator.validateTaskState(taskId));
    }

    public TaskScheduler getScheduler() {
        return this.taskScheduler;
    }

    public void addTaskCreatedListener(Consumer<Task> listener) {
        eventPublisher.addTaskCreatedListener(listener);
    }

    public void addTaskAssignedListener(Consumer<Task> listener) {
        eventPublisher.addTaskAssignedListener(listener);
    }

    /** Called by assignment listeners when a task transitions READY → RUNNING. */
    public void publishTaskAssigned(Task task) {
        eventPublisher.publishTaskAssigned(task);
    }

    public void addTaskReadyListener(Consumer<Task> listener) {
        eventPublisher.addTaskReadyListener(listener);
    }

    public void addTaskDispatchListener(Consumer<Task> listener) {
        eventPublisher.addTaskDispatchListener(listener);
    }

    public void addTaskTerminalListener(Consumer<Task> listener) {
        eventPublisher.addTaskTerminalListener(listener);
    }

    public void addTaskMessageAttemptClosedListener(TaskMessageAttemptClosedListener listener) {
        eventPublisher.addTaskMessageAttemptClosedListener(listener);
    }

    public void addTaskMessageLogicallyFinalListener(TaskMessageLogicallyFinalListener listener) {
        eventPublisher.addTaskMessageLogicallyFinalListener(listener);
    }

    public boolean handleTaskMessageResult(String taskId, String msgId, boolean success, String detail) {
        return withTaskLock(taskId, () -> resultService.handleTaskMessageResult(taskId, msgId, success, detail));
    }

    public boolean handleTaskMessageResult(String taskId, String msgId, boolean success, String detail, String errorCode) {
        return withTaskLock(taskId, () -> resultService.handleTaskMessageResult(taskId, msgId, success, detail, errorCode));
    }

    public boolean handleTaskMessageResult(String taskId,
                                           String msgId,
                                           boolean success,
                                           String detail,
                                           String errorCode,
                                           Map<String, Object> output) {
        return withTaskLock(taskId, () -> resultService.handleTaskMessageResult(taskId, msgId, success, detail, errorCode, output));
    }

    <T> T withTaskLock(String taskId, Supplier<T> action) {
        if (taskId == null || taskId.isBlank()) {
            return action.get();
        }
        ReentrantLock lock = taskLocks.computeIfAbsent(taskId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
            if (!lock.isLocked() && !lock.hasQueuedThreads()) {
                taskLocks.remove(taskId, lock);
            }
        }
    }

    void withTaskLock(String taskId, Runnable action) {
        withTaskLock(taskId, () -> {
            action.run();
            return null;
        });
    }

    private void validateCreateRequest(TaskCreateRequestDto dto) {
        if (dto == null) {
            throw new IllegalArgumentException("task request body is required");
        }
        ProjectRef.require(dto.getProject());
        UserRef.requireUserId(dto.getUserId());
    }

    TaskStorage getTaskStorage() {
        return taskStorage;
    }

    TaskTerminalPolicy getTaskTerminalPolicy() {
        return taskTerminalPolicy;
    }

    TaskEventPublisher getEventPublisher() {
        return eventPublisher;
    }
}
