package com.xa.mass.engine;

import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.storage.TaskStorage;
import com.xa.mass.engine.storage.TaskStorageFactory;
import com.xa.mass.engine.strategy.TaskScheduler;
import com.xa.mass.eventbus.enums.task.TaskStatus;
import com.xa.mass.eventbus.model.Task;
import com.xa.mass.eventbus.model.TaskMsg;
import com.xa.mass.eventbus.model.User;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 任务管理器
 * 负责任务的CRUD操作和状态管理
 */
public class TaskManager {
    
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
        // 1. 生成唯一任务ID
        String tid = java.util.UUID.randomUUID().toString();

        // 2. 构建User对象（User类只有name和price字段）
        User user = new User();
        user.setName(dto.getUserId()); // 用userId作为name存储

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
        taskStorage.saveTask(task);

        return task;
    }
    
    /**
     * 获取任务
     */
    public Task getTask(String taskId) {
        return taskStorage.getTask(taskId).orElse(null);
    }
    
    /**
     * 更新任务
     */
    public boolean updateTask(Task task) {
        return taskStorage.updateTask(task);
    }
    
    /**
     * 删除任务
     */
    public boolean deleteTask(String taskId) {
        return taskStorage.deleteTask(taskId);
    }
    
    /**
     * 获取所有任务
     */
    public List<Task> getAllTasks() {
        return taskStorage.getAllTasks();
    }
    
    /**
     * 根据状态获取任务
     */
    public List<Task> getTasksByStatus(TaskStatus status) {
        return taskStorage.getTasksByStatus(status.name());
    }
    
    /**
     * 获取可调度的任务
     */
    public List<Task> getSchedulableTasks() {
        return taskStorage.getSchedulableTasks();
    }
    
    /**
     * 审核任务
     */
    public boolean approveTask(String taskId) {
        Task task = getTask(taskId);
        if (task != null && task.getStatus() == TaskStatus.NEW) {
            boolean result = task.transitionTo(TaskStatus.READY);
            if (result) {
                taskStorage.updateTask(task);
            }
            return result;
        }
        return false;
    }
    
    /**
     * 拒绝任务
     */
    public boolean rejectTask(String taskId) {
        Task task = getTask(taskId);
        if (task != null && task.getStatus() == TaskStatus.NEW) {
            boolean result = task.transitionTo(TaskStatus.TERMINAL);
            if (result) {
                taskStorage.updateTask(task);
            }
            return result;
        }
        return false;
    }
    
    /**
     * 暂停任务
     */
    public boolean pauseTask(String taskId) {
        Task task = getTask(taskId);
        if (task != null && (task.getStatus() == TaskStatus.READY || task.getStatus() == TaskStatus.RUNNING)) {
            boolean result = task.transitionTo(TaskStatus.BLOCKED);
            if (result) {
                taskStorage.updateTask(task);
                taskScheduler.pauseTask(taskId);
            }
            return result;
        }
        return false;
    }
    
    /**
     * 恢复任务
     */
    public boolean resumeTask(String taskId) {
        Task task = getTask(taskId);
        if (task != null && task.getStatus() == TaskStatus.BLOCKED) {
            boolean result = task.transitionTo(TaskStatus.READY);
            if (result) {
                taskStorage.updateTask(task);
                taskScheduler.resumeTask(taskId);
            }
            return result;
        }
        return false;
    }
    
    /**
     * 取消任务
     */
    public boolean cancelTask(String taskId) {
        Task task = getTask(taskId);
        if (task != null && !task.getStatus().isFinal()) {
            boolean result = task.transitionTo(TaskStatus.TERMINAL);
            if (result) {
                taskStorage.updateTask(task);
                taskScheduler.cancelTask(taskId);
            }
            return result;
        }
        return false;
    }
    
    /**
     * 添加任务消息
     */
    public void addTaskMessage(String taskId, TaskMsg taskMsg) {
        taskStorage.addTaskMessage(taskId, taskMsg);
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
}
