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
import com.xa.mass.engine.storage.TaskStorage;
import com.xa.mass.engine.storage.TaskStorageFactory;
import com.xa.mass.engine.strategy.TaskScheduler;
import com.xa.mass.engine.util.LogUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 任务管理器
 * 负责任务的CRUD操作和状态管理
 */
public class TaskManager {

    private static final Logger logger = LoggerFactory.getLogger(TaskManager.class);

    private final TaskStorage taskStorage;
    private final TaskScheduler taskScheduler;
    private final List<Consumer<Task>> taskReadyListeners = new CopyOnWriteArrayList<>();

    public TaskManager(TaskScheduler taskScheduler) {
        this(taskScheduler, TaskStorageFactory.createDefaultTaskStorage());
    }

    public TaskManager(TaskScheduler taskScheduler, TaskStorage taskStorage) {
        this.taskScheduler = taskScheduler;
        this.taskStorage = taskStorage;
    }

    /**
     * 创建任务
     */
    public Task createTask(TaskCreateRequestDto dto) {
        long startTime = System.currentTimeMillis();
        LogUtils.logOperationStart("CREATE_TASK", "TaskManager",
                "taskName", dto.getTaskName(),
                "project", dto.getProject(),
                "countryCode", dto.getCountryCode());

        try {
            // 1. 生成唯一任务ID
            String tid = java.util.UUID.randomUUID().toString();
            LogUtils.setTaskId(tid);

            // 2. 构建User对象（User类只有name和price字段）
            User user = new User();
            user.setName(dto.getUserId()); // 用userId作为name存储
            LogUtils.setUserId(dto.getUserId());

            // 3. 统计初始消息数
            List<String> targets = dto.getTargetList() == null ? Collections.emptyList() : dto.getTargetList();
            if (dto.getTargetJsonList() != null && !dto.getTargetJsonList().isEmpty()) {
                throw new UnsupportedOperationException("targetJsonList is not supported by the current runtime");
            }
            if (targets.isEmpty()) {
                throw new IllegalArgumentException("targetList must contain at least one target");
            }
            int initNumber = targets.size();

            // 4. 构建Task对象
            Task task = new Task(
                    tid,
                    dto.getTaskName(),
                    dto.getProject(),
                    dto.getCountryCode(),
                    initNumber,
                    dto.getTextContent(),
                    user
            );
            task.setBatchSize(dto.getBatchSize());
            // 可扩展设置 extraParams、targetType 等

            // 5. 存储任务
            taskStorage.saveTask(task);
            for (String target : targets) {
                String msgId = java.util.UUID.randomUUID().toString();
                addTaskMessage(tid, new TaskMsg(msgId, tid, target));
            }

            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationSuccess("任务创建成功，ID: " + tid + ", 消息数: " + initNumber, duration);

            return task;

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure("TASK_CREATE_ERROR", e.getMessage(), duration);
            logger.error("创建任务失败", e);
            throw e;
        }
    }

    /**
     * 获取任务
     */
    public Task getTask(String taskId) {
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("GET_TASK", "TaskManager", "taskId", taskId);

        Task task = taskStorage.getTask(taskId).orElse(null);

        if (task != null) {
            LogUtils.logOperationSuccess("任务获取成功", 0);
        } else {
            LogUtils.logOperationFailure("TASK_NOT_FOUND", "任务不存在", 0);
        }

        return task;
    }

    /**
     * 更新任务
     */
    public boolean updateTask(Task task) {
        LogUtils.setTaskId(task.getTid());
        LogUtils.logOperationStart("UPDATE_TASK", "TaskManager", "taskId", task.getTid());

        boolean result = taskStorage.updateTask(task);

        if (result) {
            LogUtils.logOperationSuccess("任务更新成功", 0);
        } else {
            LogUtils.logOperationFailure("TASK_UPDATE_ERROR", "任务更新失败", 0);
        }

        return result;
    }

    /**
     * 删除任务
     */
    public boolean deleteTask(String taskId) {
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("DELETE_TASK", "TaskManager", "taskId", taskId);

        Task task = taskStorage.getTask(taskId).orElse(null);
        if (task == null) {
            LogUtils.logOperationFailure("TASK_DELETE_ERROR", "任务不存在", 0);
            return false;
        }
        // Only NEW and TERMINAL tasks can be deleted safely.
        // Deleting a READY/RUNNING/PAUSED task would leave in-progress work orphaned.
        com.xa.mass.base.enums.task.TaskStatus status = task.getStatus();
        if (status != com.xa.mass.base.enums.task.TaskStatus.NEW
                && status != com.xa.mass.base.enums.task.TaskStatus.TERMINAL) {
            logger.warn("拒绝删除非终态任务: taskId={}, status={}", taskId, status);
            LogUtils.logOperationFailure("TASK_DELETE_REJECTED",
                    "任务状态 " + status + " 不允许删除，请先终止任务", 0);
            return false;
        }

        boolean result = taskStorage.deleteTask(taskId);

        if (result) {
            LogUtils.logOperationSuccess("任务删除成功", 0);
        } else {
            LogUtils.logOperationFailure("TASK_DELETE_ERROR", "任务删除失败", 0);
        }

        return result;
    }

    /**
     * 获取所有任务
     */
    public List<Task> getAllTasks() {
        LogUtils.logOperationStart("GET_ALL_TASKS", "TaskManager");

        List<Task> tasks = taskStorage.getAllTasks();

        LogUtils.logOperationSuccess("获取所有任务成功，数量: " + tasks.size(), 0);

        return tasks;
    }

    /**
     * 根据状态获取任务
     */
    public List<Task> getTasksByStatus(TaskStatus status) {
        LogUtils.logOperationStart("GET_TASKS_BY_STATUS", "TaskManager", "status", status.name());

        List<Task> tasks = taskStorage.getTasksByStatus(status.name());

        LogUtils.logOperationSuccess("根据状态获取任务成功，数量: " + tasks.size(), 0);

        return tasks;
    }

    /**
     * 获取可调度的任务
     */
    public List<Task> getSchedulableTasks() {
        LogUtils.logOperationStart("GET_SCHEDULABLE_TASKS", "TaskManager");

        List<Task> tasks = taskStorage.getSchedulableTasks();

        LogUtils.logOperationSuccess("获取可调度任务成功，数量: " + tasks.size(), 0);

        return tasks;
    }

    /**
     * 审核任务
     */
    public boolean approveTask(String taskId) {
        long startTime = System.currentTimeMillis();
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("APPROVE_TASK", "TaskManager", "taskId", taskId);

        try {
            Task task = getTask(taskId);
            if (task != null
                    && (task.getStatus() == TaskStatus.NEW || task.getStatus() == TaskStatus.BLOCKED)) {
                boolean result = task.transitionTo(TaskStatus.READY);
                if (result) {
                    taskStorage.updateTask(task);
                    notifyTaskReady(task);
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationSuccess("任务审核通过", duration);
                } else {
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationFailure("TASK_APPROVE_ERROR", "任务状态转换失败", duration);
                }
                return result;
            } else {
                long duration = System.currentTimeMillis() - startTime;
                LogUtils.logOperationFailure("TASK_APPROVE_ERROR", "任务不存在或状态不允许审核", duration);
                return false;
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure("TASK_APPROVE_ERROR", e.getMessage(), duration);
            logger.error("审核任务失败", e);
            return false;
        }
    }

    /**
     * 拒绝任务
     */
    public boolean rejectTask(String taskId) {
        long startTime = System.currentTimeMillis();
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("REJECT_TASK", "TaskManager", "taskId", taskId);

        try {
            Task task = getTask(taskId);
            if (task != null && task.getStatus() == TaskStatus.NEW) {
                boolean result = task.transitionTo(TaskStatus.BLOCKED);
                if (result) {
                    taskStorage.updateTask(task);
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationSuccess("任务拒绝成功，已阻塞", duration);
                } else {
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationFailure("TASK_REJECT_ERROR", "任务状态转换失败", duration);
                }
                return result;
            } else {
                long duration = System.currentTimeMillis() - startTime;
                LogUtils.logOperationFailure("TASK_REJECT_ERROR", "任务不存在或状态不允许拒绝", duration);
                return false;
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure("TASK_REJECT_ERROR", e.getMessage(), duration);
            logger.error("拒绝任务失败", e);
            return false;
        }
    }

    /**
     * 阻塞任务（READY 或 RUNNING → BLOCKED）。
     * <p>
     * 与 {@link #rejectTask(String)}（仅 NEW→BLOCKED）不同，此方法用于已进入调度流程
     * 但因临时资源不足、策略限制等原因需要暂缓的任务。
     * 阻塞后可通过 {@link #approveTask(String)} 恢复到 READY。
     */
    public boolean blockTask(String taskId) {
        long startTime = System.currentTimeMillis();
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("BLOCK_TASK", "TaskManager", "taskId", taskId);

        try {
            Task task = getTask(taskId);
            if (task != null
                    && (task.getStatus() == TaskStatus.READY || task.getStatus() == TaskStatus.RUNNING)) {
                boolean result = task.transitionTo(TaskStatus.BLOCKED);
                if (result) {
                    taskStorage.updateTask(task);
                    taskScheduler.pauseTask(taskId); // stop scheduling while blocked
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationSuccess("任务已阻塞", duration);
                } else {
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationFailure("TASK_BLOCK_ERROR", "任务状态转换失败", duration);
                }
                return result;
            } else {
                long duration = System.currentTimeMillis() - startTime;
                LogUtils.logOperationFailure("TASK_BLOCK_ERROR", "任务不存在或状态不允许阻塞", duration);
                return false;
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure("TASK_BLOCK_ERROR", e.getMessage(), duration);
            logger.error("阻塞任务失败", e);
            return false;
        }
    }

    /**
     * 暂停任务
     */
    public boolean pauseTask(String taskId) {
        long startTime = System.currentTimeMillis();
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("PAUSE_TASK", "TaskManager", "taskId", taskId);

        try {
            Task task = getTask(taskId);
            if (task != null && (task.getStatus() == TaskStatus.READY || task.getStatus() == TaskStatus.RUNNING)) {
                boolean result = task.transitionTo(TaskStatus.PAUSED);
                if (result) {
                    taskStorage.updateTask(task);
                    taskScheduler.pauseTask(taskId);
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationSuccess("任务暂停成功", duration);
                } else {
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationFailure("TASK_PAUSE_ERROR", "任务状态转换失败", duration);
                }
                return result;
            } else {
                long duration = System.currentTimeMillis() - startTime;
                LogUtils.logOperationFailure("TASK_PAUSE_ERROR", "任务不存在或状态不允许暂停", duration);
                return false;
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure("TASK_PAUSE_ERROR", e.getMessage(), duration);
            logger.error("暂停任务失败", e);
            return false;
        }
    }

    /**
     * 恢复任务。
     * <p>
     * 注意：此方法有两条路径，调用方需通过后续 {@link #getTask(String)} 确认实际结果：
     * <ul>
     *   <li>若恢复时所有消息均已完成 → 任务直接进入 TERMINAL（无需重新调度）</li>
     *   <li>否则 → 任务进入 READY，重新进入调度队列</li>
     * </ul>
     */
    public TaskResumeResult resumeTaskDetailed(String taskId) {
        long startTime = System.currentTimeMillis();
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("RESUME_TASK", "TaskManager", "taskId", taskId);

        try {
            Task task = getTask(taskId);
            if (task != null && task.getStatus() == TaskStatus.PAUSED) {
                TaskStorage.TaskMessageStats stats = getTaskMessageStats(taskId);
                if (allTaskMessagesCompleted(stats)) {
                    // All messages finished while the task was paused — terminate directly
                    // instead of re-queuing. Callers should check getTask() to distinguish
                    // this PAUSED→TERMINAL path from the normal PAUSED→READY path.
                    task.setTaskSuccessNumber((int) stats.getSuccess());
                    TaskTerminalReason terminalReason = determineTerminalReason(stats);
                    boolean result = task.transitionTo(TaskStatus.TERMINAL, terminalReason);
                    if (result) {
                        taskStorage.updateTask(task);
                        long duration = System.currentTimeMillis() - startTime;
                        LogUtils.logOperationSuccess("任务暂停期间已全部完成，直接终止 (PAUSED→TERMINAL)", duration);
                        return TaskResumeResult.completedToTerminal(terminalReason);
                    } else {
                        long duration = System.currentTimeMillis() - startTime;
                        LogUtils.logOperationFailure("TASK_RESUME_ERROR", "任务已完成但收尾失败", duration);
                    }
                    return TaskResumeResult.rejected();
                }
                boolean result = task.transitionTo(TaskStatus.READY);
                if (result) {
                    taskStorage.updateTask(task);
                    taskScheduler.resumeTask(taskId);
                    notifyTaskReady(task);
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationSuccess("任务恢复成功", duration);
                    return TaskResumeResult.resumedToReady();
                } else {
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationFailure("TASK_RESUME_ERROR", "任务状态转换失败", duration);
                }
                return TaskResumeResult.rejected();
            } else {
                long duration = System.currentTimeMillis() - startTime;
                LogUtils.logOperationFailure("TASK_RESUME_ERROR", "任务不存在或状态不允许恢复", duration);
                return TaskResumeResult.rejected();
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure("TASK_RESUME_ERROR", e.getMessage(), duration);
            logger.error("恢复任务失败", e);
            return TaskResumeResult.rejected();
        }
    }

    public boolean resumeTask(String taskId) {
        return resumeTaskDetailed(taskId).isSuccess();
    }

    /**
     * 取消任务
     */
    public boolean cancelTask(String taskId) {
        long startTime = System.currentTimeMillis();
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("CANCEL_TASK", "TaskManager", "taskId", taskId);

        try {
            Task task = getTask(taskId);
            if (task != null && !task.getStatus().isFinal()) {
                boolean result = task.transitionTo(TaskStatus.TERMINAL, TaskTerminalReason.MANUAL_CANCELLED);
                if (result) {
                    taskStorage.updateTask(task);
                    cancelPendingMessages(taskId); // drain non-final messages to a terminal state
                    taskScheduler.cancelTask(taskId);
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationSuccess("任务取消成功", duration);
                } else {
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationFailure("TASK_CANCEL_ERROR", "任务状态转换失败", duration);
                }
                return result;
            } else {
                long duration = System.currentTimeMillis() - startTime;
                LogUtils.logOperationFailure("TASK_CANCEL_ERROR", "任务不存在或状态不允许取消", duration);
                return false;
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure("TASK_CANCEL_ERROR", e.getMessage(), duration);
            logger.error("取消任务失败", e);
            return false;
        }
    }

    /**
     * 添加任务消息
     */
    public void addTaskMessage(String taskId, TaskMsg taskMsg) {
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("ADD_TASK_MESSAGE", "TaskManager",
                "taskId", taskId,
                "messageId", taskMsg.getMsgId());

        taskStorage.addTaskMessage(taskId, taskMsg);

        LogUtils.logOperationSuccess("任务消息添加成功", 0);
    }

    /**
     * 获取任务的所有消息
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
     * 将单条消息标记为 EXPIRED，并触发任务进度重算。
     * <p>
     * 仅 SENT 或 RUNNING 状态的消息可以过期（已下发但超时无回执）。
     * INIT / BINDING 阶段的消息尚未真正下发，应通过 {@link #cancelTask} 清理，
     * 不能通过此方法过期。
     *
     * @return true 表示成功过期，false 表示消息不存在、状态不允许或持久化失败
     */
    public boolean expireTaskMessage(String taskId, String msgId) {
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("EXPIRE_TASK_MESSAGE", "TaskManager",
                "taskId", taskId, "msgId", msgId);

        TaskMsg taskMsg = getTaskMessage(taskId, msgId);
        if (taskMsg == null) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "消息不存在", 0);
            return false;
        }
        if (taskMsg.isCompleted()) {
            logger.info("Task message {} of task {} is already in final status {}, skip expiry",
                    msgId, taskId, taskMsg.getStatus());
            return false;
        }
        // Only dispatched messages (SENT / RUNNING) can be expired.
        // INIT / BINDING never left the engine, so they should not be expired.
        boolean expired = taskMsg.markAsExpired();
        if (!expired) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR",
                    "消息状态 " + taskMsg.getStatus() + " 不允许过期（仅 SENT/RUNNING 可过期）", 0);
            return false;
        }
        boolean stored = updateTaskMessage(taskId, taskMsg);
        if (!stored) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "消息持久化失败", 0);
            return false;
        }
        LogUtils.logOperationSuccess("消息已过期", 0);
        updateTaskProgress(taskId); // may transition task to TERMINAL if all messages are now done
        return true;
    }

    /**
     * 获取任务消息统计
     */
    public TaskStorage.TaskMessageStats getTaskMessageStats(String taskId) {
        return taskStorage.getTaskMessageStats(taskId);
    }

    /**
     * 更新任务进度
     */
    public void updateTaskProgress(String taskId) {
        resolveTaskStateFromMessages(taskId);
    }

    /**
     * 根据当前 TaskMsg 聚合结果显式解析任务状态。
     * 这是 SDK 侧应该依赖的主入口，而不是猜测 updateTaskProgress() 的内部行为。
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
            return TaskStateResolutionResult.alreadyFinal(
                    task.getStatus(),
                    task.getTerminalReason(),
                    stats.getTotal(),
                    stats.getSuccess(),
                    stats.getFailed()
            );
        }

        if (!allTaskMessagesCompleted(stats)) {
            taskStorage.updateTask(task);
            return TaskStateResolutionResult.notFinalized(
                    task.getStatus(),
                    stats.getTotal(),
                    stats.getSuccess(),
                    stats.getFailed()
            );
        }

        TaskTerminalReason reason = determineTerminalReason(stats);
        boolean result = task.transitionTo(TaskStatus.TERMINAL, reason);
        if (result) {
            taskStorage.updateTask(task);
            return TaskStateResolutionResult.finalizedToTerminal(
                    reason,
                    stats.getTotal(),
                    stats.getSuccess(),
                    stats.getFailed()
            );
        }

        taskStorage.updateTask(task);
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
                    if (!(stats.getTotal() > 0 && stats.getSuccess() == stats.getTotal() && stats.getFailed() == 0 && stats.getProcessing() == 0)) {
                        violations.add(TaskStateValidationResult.ViolationCode.TERMINAL_REASON_MISMATCH_ALL_SUCCEEDED);
                    }
                }
                case ALL_MESSAGES_FAILED -> {
                    if (!(stats.getTotal() > 0 && stats.getFailed() == stats.getTotal() && stats.getSuccess() == 0 && stats.getProcessing() == 0)) {
                        violations.add(TaskStateValidationResult.ViolationCode.TERMINAL_REASON_MISMATCH_ALL_FAILED);
                    }
                }
                case MIXED_MESSAGE_RESULTS -> {
                    boolean mixed = stats.getTotal() > 0
                            && stats.getSuccess() > 0
                            && stats.getFailed() > 0
                            && stats.getSuccess() + stats.getFailed() == stats.getTotal()
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

        boolean needsResolution = !finalStatus && allTaskMessagesCompleted(stats);
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

    public TaskScheduler getScheduler() {
        return this.taskScheduler;
    }

    public void addTaskReadyListener(Consumer<Task> listener) {
        if (listener != null) {
            taskReadyListeners.add(listener);
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
            logger.info("Task message {} of task {} is already in final status {}, skipping duplicate result",
                    msgId, taskId, taskMsg.getStatus());
            updateTaskProgress(taskId);
            return true;
        }

        if (task.getStatus().isFinal()) {
            logger.info("Ignoring late result for terminal task {}, msg {} still in status {}",
                    taskId, msgId, taskMsg.getStatus());
            return true;
        }

        if (!advanceTaskMsgForCompletion(taskMsg, success)) {
            logger.warn("Cannot advance task message {} from status {} for completion",
                    msgId, taskMsg.getStatus());
            return false;
        }

        boolean statusUpdated = success ? taskMsg.markAsSuccess(detail) : taskMsg.markAsFailed(detail);
        if (!statusUpdated) {
            logger.warn("Failed to mark task message {} as {}", msgId, success ? "SUCCESS" : "FAILED");
            return false;
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
        // Always advance through INIT→BINDING regardless of outcome.
        if (status == TaskMsgStatus.INIT && !taskMsg.transitionTo(TaskMsgStatus.BINDING)) {
            return false;
        }
        status = taskMsg.getStatus();
        // Always advance BINDING→SENT regardless of success/failure so the
        // final markAsSuccess/markAsFailed is always called from RUNNING state.
        // (BINDING→FAILED is technically allowed by the state machine but skips
        // the RUNNING stage that callers expect to see in logs/metrics.)
        if (status == TaskMsgStatus.BINDING) {
            if (!taskMsg.markAsSent()) {
                return false;
            }
            status = taskMsg.getStatus();
        }
        // Always advance SENT→RUNNING before the caller applies the terminal mark.
        if (status == TaskMsgStatus.SENT) {
            return taskMsg.markAsRunning();
        }
        return true;
    }

    private boolean allTaskMessagesCompleted(TaskStorage.TaskMessageStats stats) {
        return stats.getTotal() > 0 && stats.getSuccess() + stats.getFailed() == stats.getTotal();
    }

    private TaskTerminalReason determineTerminalReason(TaskStorage.TaskMessageStats stats) {
        if (stats.getSuccess() == stats.getTotal()) {
            return TaskTerminalReason.ALL_MESSAGES_SUCCEEDED;
        }
        if (stats.getFailed() == stats.getTotal()) {
            return TaskTerminalReason.ALL_MESSAGES_FAILED;
        }
        return TaskTerminalReason.MIXED_MESSAGE_RESULTS;
    }

    /**
     * 将任务所有非终态消息强制转换到终态，在 cancelTask 后调用。
     * <ul>
     *   <li>INIT / BINDING — 从未真正下发，标记为 FAILED</li>
     *   <li>SENT / RUNNING  — 已下发但被中止，标记为 EXPIRED</li>
     * </ul>
     */
    private void cancelPendingMessages(String taskId) {
        List<TaskMsg> messages = getTaskMessages(taskId);
        for (TaskMsg msg : messages) {
            if (msg.isCompleted()) continue;

            TaskMsgStatus s = msg.getStatus();
            boolean updated = false;
            if (s == TaskMsgStatus.INIT) {
                // INIT can only go to BINDING first, then BINDING→FAILED
                msg.transitionTo(TaskMsgStatus.BINDING);
                updated = msg.markAsFailed("task cancelled");
            } else if (s == TaskMsgStatus.BINDING) {
                updated = msg.markAsFailed("task cancelled");
            } else if (s == TaskMsgStatus.SENT || s == TaskMsgStatus.RUNNING) {
                updated = msg.markAsExpired();
            }
            if (updated) {
                updateTaskMessage(taskId, msg);
            }
        }
    }
}
