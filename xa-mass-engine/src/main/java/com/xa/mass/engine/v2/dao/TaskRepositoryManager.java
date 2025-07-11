package com.xa.mass.engine.v2.dao;

import com.xa.mass.base.channel.queue.MessageQueueProviderRegistry;
import com.xa.mass.base.channel.queue.QueueProviderType;
import com.xa.mass.base.channel.queue.api.MessageMap;
import com.xa.mass.base.channel.queue.api.MessageQueue;
import com.xa.mass.base.channel.queue.memory.InMemoryMessageMap;
import com.xa.mass.base.enums.Project;
import com.xa.mass.engine.v2.entity.TaskEntity;
import com.xa.mass.engine.v2.entity.TaskMsgEntity;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 任务仓储管理器 v2
 * 支持项目隔离的任务存储和队列管理
 */
public class TaskRepositoryManager {

    // 外层 key: project，内层 key: taskId
    private final ConcurrentMap<Project, MessageMap<String, TaskEntity>> projectTaskMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, MessageQueue<String>> seedQueues = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, MessageQueue<TaskMsgEntity>> msgQueues = new ConcurrentHashMap<>();
    private final QueueProviderType seedQueueType;
    private final QueueProviderType msgQueueType;

    // 构造函数，直接传入项目-任务Map
    public TaskRepositoryManager( QueueProviderType queueType) {
        this( queueType, queueType);
    }

    public TaskRepositoryManager(QueueProviderType seedQueueType, QueueProviderType msgQueueType) {
        this.seedQueueType = Objects.requireNonNull(seedQueueType);
        this.msgQueueType = Objects.requireNonNull(msgQueueType);
    }

    // 任务实体操作
    public void saveTask(Project project, TaskEntity taskEntity) {
        projectTaskMap.computeIfAbsent(project, k -> new InMemoryMessageMap<>())
                      .put(taskEntity.getTaskId(), taskEntity);
    }

    public TaskEntity getTask(Project project, String taskId) {
        MessageMap<String, TaskEntity> map = projectTaskMap.get(project);
        return map != null ? map.get(taskId) : null;
    }

    public boolean containsTask(Project project, String taskId) {
        MessageMap<String, TaskEntity> map = projectTaskMap.get(project);
        return map != null && map.containsKey(taskId);
    }

    public TaskEntity removeTask(Project project, String taskId) {
        MessageMap<String, TaskEntity> map = projectTaskMap.get(project);
        return map != null ? map.remove(taskId) : null;
    }

    public int getProjectTaskCount(Project project) {
        MessageMap<String, TaskEntity> map = projectTaskMap.get(project);
        return map != null ? map.size() : 0;
    }

    public int getTotalTaskCount() {
        return projectTaskMap.values().stream().mapToInt(MessageMap::size).sum();
    }

    /**
     * 注册所有项目分组
     */
    public void registerAllProjects(java.util.function.Function<Project, MessageMap<String, TaskEntity>> mapSupplier) {
        Objects.requireNonNull(mapSupplier, "Map supplier cannot be null");
        for (Project project : Project.values()) {
            registerProject(project, mapSupplier.apply(project));
        }
    }

    /**
     * 注册单个项目
     */
    public void registerProject(Project project, MessageMap<String, TaskEntity> taskMap) {
        Objects.requireNonNull(project, "Project cannot be null");
        Objects.requireNonNull(taskMap, "Task map cannot be null");
        projectTaskMap.put(project, taskMap);
    }

    /**
     * 便捷构造器：使用默认的内存队列为所有项目初始化
     */
    public static TaskRepositoryManager createWithDefaultProjects(QueueProviderType queueType) {
        return createWithDefaultProjects(queueType, queueType);
    }

    /**
     * 便捷构造器：使用默认的内存队列为所有项目初始化
     */
    public static TaskRepositoryManager createWithDefaultProjects(QueueProviderType seedQueueType, QueueProviderType msgQueueType) {
        TaskRepositoryManager manager = new TaskRepositoryManager( seedQueueType, msgQueueType);
        manager.registerAllProjects(project -> new InMemoryMessageMap<>());
        return manager;
    }

    // 种子队列操作
    public void createSeedQueue(String taskId) {
        MessageQueue<String> queue = MessageQueueProviderRegistry.createQueue(seedQueueType, taskId + ":seeds");
        seedQueues.put(taskId, queue);
    }

    public void addSeed(String taskId, String seed) {
        MessageQueue<String> queue = seedQueues.get(taskId);
        if (queue != null) {
            queue.offer(seed);
        }
    }

    public String getSeed(String taskId) {
        MessageQueue<String> queue = seedQueues.get(taskId);
        if (queue != null) {
            try {
                return queue.poll(0, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    public int getSeedCount(String taskId) {
        MessageQueue<String> queue = seedQueues.get(taskId);
        return queue != null ? queue.size() : 0;
    }

    // 消息队列操作  
    public void createMsgQueue(String taskId) {
        MessageQueue<TaskMsgEntity> queue = MessageQueueProviderRegistry.createQueue(msgQueueType, taskId + ":msgs");
        msgQueues.put(taskId, queue);
    }

    public void addMsg(String taskId, TaskMsgEntity msg) {
        MessageQueue<TaskMsgEntity> queue = msgQueues.get(taskId);
        if (queue != null) {
            queue.offer(msg);
        }
    }

    public TaskMsgEntity getMsg(String taskId) {
        MessageQueue<TaskMsgEntity> queue = msgQueues.get(taskId);
        if (queue != null) {
            try {
                return queue.poll(0, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    public int getMsgCount(String taskId) {
        MessageQueue<TaskMsgEntity> queue = msgQueues.get(taskId);
        return queue != null ? queue.size() : 0;
    }
}
