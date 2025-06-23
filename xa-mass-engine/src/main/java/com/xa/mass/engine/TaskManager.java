package com.xa.mass.core.engine;

import com.xa.mass.core.engine.model.task.Task;
import com.xa.mass.core.engine.model.TaskMsg;
import com.xa.mass.engine.model.common.User;
import com.xa.mass.engine.model.enums.TaskStatus;
import com.xa.mass.core.engine.model.task.TaskCreateRequestDto;
import com.xa.mass.core.engine.strategy.TaskScheduler;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 任务管理器
 * 负责任务的CRUD操作和状态管理
 */
public class TaskManager {
    
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final Map<String, List<TaskMsg>> taskMessages = new ConcurrentHashMap<>();
    private final TaskScheduler taskScheduler;
    
    public TaskManager(TaskScheduler taskScheduler) {
        this.taskScheduler = taskScheduler;
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
        tasks.put(tid, task);
        taskMessages.put(tid, new java.util.concurrent.CopyOnWriteArrayList<>());

        return task;
    }
    
    /**
     * 获取任务
     */
    public Task getTask(String taskId) {
        return tasks.get(taskId);
    }
    
    /**
     * 更新任务
     */
    public boolean updateTask(Task task) {
        if (task.getTid() == null || !tasks.containsKey(task.getTid())) {
            return false;
        }
        
        tasks.put(task.getTid(), task);
        return true;
    }
    
    /**
     * 删除任务
     */
    public boolean deleteTask(String taskId) {
        Task removed = tasks.remove(taskId);
        taskMessages.remove(taskId);
        return removed != null;
    }
    
    /**
     * 获取所有任务
     */
    public List<Task> getAllTasks() {
        return new CopyOnWriteArrayList<>(tasks.values());
    }
    
    /**
     * 根据状态获取任务
     */
    public List<Task> getTasksByStatus(TaskStatus status) {
        return tasks.values().stream()
                .filter(task -> task.getStatus() == status)
                .collect(Collectors.toList());
    }
    
    /**
     * 获取可调度的任务
     */
    public List<Task> getSchedulableTasks() {
        return tasks.values().stream()
                .filter(Task::isSchedulable)
                .collect(Collectors.toList());
    }
    
    /**
     * 审核任务
     */
    public boolean approveTask(String taskId) {
        Task task = tasks.get(taskId);
        if (task != null && task.getStatus() == TaskStatus.NEW) {
            return task.transitionTo(TaskStatus.READY);
        }
        return false;
    }
    
    /**
     * 拒绝任务
     */
    public boolean rejectTask(String taskId) {
        Task task = tasks.get(taskId);
        if (task != null && task.getStatus() == TaskStatus.NEW) {
            return task.transitionTo(TaskStatus.TERMINAL);
        }
        return false;
    }
    
    /**
     * 暂停任务
     */
    public boolean pauseTask(String taskId) {
        Task task = tasks.get(taskId);
        if (task != null && (task.getStatus() == TaskStatus.READY || task.getStatus() == TaskStatus.RUNNING)) {
            boolean result = task.transitionTo(TaskStatus.BLOCKED);
            if (result) {
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
        Task task = tasks.get(taskId);
        if (task != null && task.getStatus() == TaskStatus.BLOCKED) {
            boolean result = task.transitionTo(TaskStatus.READY);
            if (result) {
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
        Task task = tasks.get(taskId);
        if (task != null && !task.getStatus().isFinal()) {
            boolean result = task.transitionTo(TaskStatus.TERMINAL);
            if (result) {
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
        List<TaskMsg> messages = taskMessages.get(taskId);
        if (messages != null) {
            messages.add(taskMsg);
        }
    }
    
    /**
     * 获取任务的所有消息
     */
    public List<TaskMsg> getTaskMessages(String taskId) {
        List<TaskMsg> messages = taskMessages.get(taskId);
        return messages != null ? new CopyOnWriteArrayList<>(messages) : new CopyOnWriteArrayList<>();
    }
    
    /**
     * 获取任务消息统计
     */
    public TaskMessageStats getTaskMessageStats(String taskId) {
        List<TaskMsg> messages = getTaskMessages(taskId);
        
        long total = messages.size();
        long success = messages.stream().filter(TaskMsg::isSuccess).count();
        long failed = messages.stream().filter(TaskMsg::isFailed).count();
        long processing = messages.stream().filter(TaskMsg::isProcessing).count();
        
        return new TaskMessageStats(total, success, failed, processing);
    }
    
    /**
     * 更新任务进度
     */
    public void updateTaskProgress(String taskId) {
        Task task = tasks.get(taskId);
        if (task != null) {
            TaskMessageStats stats = getTaskMessageStats(taskId);
            task.setTaskExecutedNumber((int) stats.getSuccess());
            
            // 如果所有消息都已完成，将任务标记为终止
            if (stats.getTotal() > 0 && stats.getSuccess() + stats.getFailed() == stats.getTotal()) {
                if (task.getStatus() == TaskStatus.RUNNING) {
                    task.transitionTo(TaskStatus.TERMINAL);
                }
            }
        }
    }
    
    /**
     * 任务消息统计
     */
    public static class TaskMessageStats {
        private final long total;
        private final long success;
        private final long failed;
        private final long processing;
        
        public TaskMessageStats(long total, long success, long failed, long processing) {
            this.total = total;
            this.success = success;
            this.failed = failed;
            this.processing = processing;
        }
        
        public long getTotal() {
            return total;
        }
        
        public long getSuccess() {
            return success;
        }
        
        public long getFailed() {
            return failed;
        }
        
        public long getProcessing() {
            return processing;
        }
        
        public double getSuccessRate() {
            return total > 0 ? (double) success / total * 100 : 0.0;
        }
        
        public double getFailureRate() {
            return total > 0 ? (double) failed / total * 100 : 0.0;
        }
    }
}
