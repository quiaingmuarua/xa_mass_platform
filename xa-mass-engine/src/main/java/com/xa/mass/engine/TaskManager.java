package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
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

import java.util.List;

/**
 * 任务管理器
 * 负责任务的CRUD操作和状态管理
 */
public class TaskManager {

    private static final Logger logger = LoggerFactory.getLogger(TaskManager.class);

    private final TaskStorage taskStorage;
    private final TaskScheduler taskScheduler;

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
            int initNumber = 0;
            if (dto.getTargetList() != null) {
                initNumber += dto.getTargetList().size();
            }
            if (dto.getTargetJsonList() != null) {
                initNumber += dto.getTargetJsonList().size();
            }

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
            // 可扩展设置 extraParams、targetType 等

            // 5. 存储任务
            String msgId = java.util.UUID.randomUUID().toString();

            taskStorage.saveTask(task);
            for (String target : dto.getTargetList()) {
                addTaskMessage(tid, new TaskMsg(tid, msgId, target));
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
            if (task != null && task.getStatus() == TaskStatus.NEW) {
                boolean result = task.transitionTo(TaskStatus.READY);
                if (result) {
                    taskStorage.updateTask(task);
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
                boolean result = task.transitionTo(TaskStatus.TERMINAL);
                if (result) {
                    taskStorage.updateTask(task);
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationSuccess("任务拒绝成功", duration);
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
     * 恢复任务
     */
    public boolean resumeTask(String taskId) {
        long startTime = System.currentTimeMillis();
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("RESUME_TASK", "TaskManager", "taskId", taskId);

        try {
            Task task = getTask(taskId);
            if (task != null && task.getStatus() == TaskStatus.PAUSED) {
                boolean result = task.transitionTo(TaskStatus.READY);
                if (result) {
                    taskStorage.updateTask(task);
                    taskScheduler.resumeTask(taskId);
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
            if (stats.getTotal() > 0 && stats.getSuccess() + stats.getFailed() == stats.getTotal()) {
                if (task.getStatus() == TaskStatus.RUNNING) {
                    boolean result = task.transitionTo(TaskStatus.TERMINAL);
                    if (result) {
                        taskStorage.updateTask(task);
                    }
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
}
