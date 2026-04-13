package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.User;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.storage.TaskStorage;
import com.xa.mass.engine.storage.TaskStorageFactory;
import com.xa.mass.engine.strategy.TaskScheduler;
import com.xa.mass.engine.util.LogUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
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
    public boolean resumeTask(String taskId) {
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
                    task.setTaskExecutedNumber((int) stats.getSuccess());
                    boolean result = task.transitionTo(TaskStatus.TERMINAL);
                    if (result) {
                        taskStorage.updateTask(task);
                        long duration = System.currentTimeMillis() - startTime;
                        LogUtils.logOperationSuccess("任务暂停期间已全部完成，直接终止 (PAUSED→TERMINAL)", duration);
                    } else {
                        long duration = System.currentTimeMillis() - startTime;
                        LogUtils.logOperationFailure("TASK_RESUME_ERROR", "任务已完成但收尾失败", duration);
                    }
                    return result;
                }
                boolean result = task.transitionTo(TaskStatus.READY);
                if (result) {
                    taskStorage.updateTask(task);
                    taskScheduler.resumeTask(taskId);
                    notifyTaskReady(task);
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationSuccess("任务恢复成功", duration);
                } else {
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationFailure("TASK_RESUME_ERROR", "任务状态转换失败", duration);
                }
                return result;
            } else {
                long duration = System.currentTimeMillis() - startTime;
                LogUtils.logOperationFailure("TASK_RESUME_ERROR", "任务不存在或状态不允许恢复", duration);
                return false;
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure("TASK_RESUME_ERROR", e.getMessage(), duration);
            logger.error("恢复任务失败", e);
            return false;
        }
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
                boolean result = task.transitionTo(TaskStatus.TERMINAL);
                if (result) {
                    taskStorage.updateTask(task);
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
     * 获取任务消息统计
     */
    public TaskStorage.TaskMessageStats getTaskMessageStats(String taskId) {
        return taskStorage.getTaskMessageStats(taskId);
    }

    /**
     * 更新任务进度
     */
    public void updateTaskProgress(String taskId) {
        Task task = getTask(taskId);
        if (task != null) {
            TaskStorage.TaskMessageStats stats = getTaskMessageStats(taskId);
            task.setTaskExecutedNumber((int) stats.getSuccess());

            // 如果所有消息都已完成，将任务标记为终止
            if (allTaskMessagesCompleted(stats)) {
                if (!task.getStatus().isFinal()) {
                    boolean result = task.transitionTo(TaskStatus.TERMINAL);
                    if (result) {
                        taskStorage.updateTask(task);
                    }
                } else {
                    // Task is already in a final state but taskExecutedNumber was just updated
                    // in memory — persist it so the stored record stays accurate.
                    taskStorage.updateTask(task);
                }
            } else {
                // 更新任务状态
                taskStorage.updateTask(task);
            }
        }
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
}
