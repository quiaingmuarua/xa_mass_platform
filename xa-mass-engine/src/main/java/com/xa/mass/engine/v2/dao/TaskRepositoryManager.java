package com.xa.mass.engine.v2.dao;

import com.xa.mass.base.channel.queue.api.MessageMap;
import com.xa.mass.base.channel.queue.api.MessageQueue;
import com.xa.mass.base.channel.queue.MessageQueueProviderRegistry;
import com.xa.mass.engine.v2.entity.TaskEntity;
import com.xa.mass.engine.v2.entity.TaskMsgEntity;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 任务仓库管理器
 * 负责任务的完整生命周期管理：创建 → 种子流式传入 → 等待审核 → 审核通过 → 绑定设备 → 生成 TaskMsgEntity
 */
public class TaskRepositoryManager {

    // 任务种子队列：Map<TaskId, MessageQueue<Seed>>
    private final ConcurrentMap<String, MessageQueue<String>> taskSeedsMap = new ConcurrentHashMap<>();

    // 任务实体映射：Map<TaskId, TaskEntity>
    private final MessageMap<String, TaskEntity> taskMap;

    // 任务消息队列：Map<TaskId, MessageQueue<TaskMsgEntity>>
    private final ConcurrentMap<String, MessageQueue<TaskMsgEntity>> taskMsgMap = new ConcurrentHashMap<>();

    // 队列提供者类型
    private final String seedQueueType;
    private final String msgQueueType;

    public TaskRepositoryManager(MessageMap<String, TaskEntity> taskMap) {
        this(taskMap, MessageQueueProviderRegistry.IN_MEMORY, MessageQueueProviderRegistry.IN_MEMORY);
    }

    public TaskRepositoryManager(MessageMap<String, TaskEntity> taskMap, String queueType) {
        this(taskMap, queueType, queueType);
    }

    public TaskRepositoryManager(MessageMap<String, TaskEntity> taskMap, String seedQueueType, String msgQueueType) {
        this.taskMap = Objects.requireNonNull(taskMap, "Task map cannot be null");
        this.seedQueueType = Objects.requireNonNull(seedQueueType, "Seed queue type cannot be null");
        this.msgQueueType = Objects.requireNonNull(msgQueueType, "Message queue type cannot be null");
    }

    /**
     * 创建任务
     * @param taskEntity 任务实体
     */
    public void createTask(TaskEntity taskEntity) {
        Objects.requireNonNull(taskEntity, "Task entity cannot be null");
        Objects.requireNonNull(taskEntity.getTaskId(), "Task ID cannot be null");
        
        taskMap.put(taskEntity.getTaskId(), taskEntity);
        
        // 使用函数式提供者创建种子队列
        taskSeedsMap.put(taskEntity.getTaskId(), MessageQueueProviderRegistry.createQueue(seedQueueType, "seed-" + taskEntity.getTaskId()));
        
        // 使用函数式提供者创建任务消息队列
        taskMsgMap.put(taskEntity.getTaskId(), MessageQueueProviderRegistry.createQueue(msgQueueType, "msg-" + taskEntity.getTaskId()));
    }

    /**
     * 添加任务种子
     * @param taskId 任务ID
     * @param seed 种子数据
     */
    public void addTaskSeed(String taskId, String seed) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        Objects.requireNonNull(seed, "Seed cannot be null");
        
        MessageQueue<String> seedQueue = taskSeedsMap.get(taskId);
        if (seedQueue == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        
        seedQueue.offer(seed);
    }

    /**
     * 获取任务种子
     * @param taskId 任务ID
     * @return 种子数据，如果没有则返回null
     */
    public String getTaskSeed(String taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        
        MessageQueue<String> seedQueue = taskSeedsMap.get(taskId);
        if (seedQueue == null) {
            return null;
        }
        
        try {
            return seedQueue.poll(0, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 更新任务状态
     * @param taskId 任务ID
     * @param status 新状态
     */
    public void updateTaskStatus(String taskId, String status) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        Objects.requireNonNull(status, "Status cannot be null");
        
        TaskEntity task = taskMap.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        
        task.setTaskStatus(status);
        task.setUpdateTime(System.currentTimeMillis());
        taskMap.put(taskId, task);
    }

    /**
     * 添加任务消息
     * @param taskId 任务ID
     * @param taskMsg 任务消息
     */
    public void addTaskMsg(String taskId, TaskMsgEntity taskMsg) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        Objects.requireNonNull(taskMsg, "Task message cannot be null");
        
        MessageQueue<TaskMsgEntity> msgQueue = taskMsgMap.get(taskId);
        if (msgQueue == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        
        msgQueue.offer(taskMsg);
    }

    /**
     * 获取任务消息
     * @param taskId 任务ID
     * @return 任务消息，如果没有则返回null
     */
    public TaskMsgEntity getTaskMsg(String taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        
        MessageQueue<TaskMsgEntity> msgQueue = taskMsgMap.get(taskId);
        if (msgQueue == null) {
            return null;
        }
        
        try {
            return msgQueue.poll(0, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 获取任务实体
     * @param taskId 任务ID
     * @return 任务实体
     */
    public TaskEntity getTask(String taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        return taskMap.get(taskId);
    }

    /**
     * 检查任务是否存在
     * @param taskId 任务ID
     * @return 是否存在
     */
    public boolean containsTask(String taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        return taskMap.containsKey(taskId);
    }

    /**
     * 获取任务种子队列大小
     * @param taskId 任务ID
     * @return 种子数量
     */
    public int getTaskSeedCount(String taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        
        MessageQueue<String> seedQueue = taskSeedsMap.get(taskId);
        return seedQueue != null ? seedQueue.size() : 0;
    }

    /**
     * 获取任务消息队列大小
     * @param taskId 任务ID
     * @return 消息数量
     */
    public int getTaskMsgCount(String taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        
        MessageQueue<TaskMsgEntity> msgQueue = taskMsgMap.get(taskId);
        return msgQueue != null ? msgQueue.size() : 0;
    }

    /**
     * 获取总任务数
     * @return 任务总数
     */
    public int getTotalTaskCount() {
        return taskMap.size();
    }
}
