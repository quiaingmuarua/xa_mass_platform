package com.xa.mass.engine.v2.dao;

import com.xa.mass.base.channel.queue.MessageQueueProviderRegistry;
import com.xa.mass.base.channel.queue.QueueProviderType;
import com.xa.mass.base.channel.queue.api.MessageMap;
import com.xa.mass.base.channel.queue.api.MessageQueue;
import com.xa.mass.engine.v2.entity.TaskEntity;
import com.xa.mass.engine.v2.entity.TaskMsgEntity;
import com.xa.mass.base.enums.Project;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 任务仓库管理器 - 纯数据操作层
 */
public class TaskRepositoryManager {

    private final ConcurrentMap<String, MessageQueue<String>> taskSeedsMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<Project, MessageMap<String, TaskEntity>> projectTaskMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, MessageQueue<TaskMsgEntity>> taskMsgMap = new ConcurrentHashMap<>();
    private final QueueProviderType seedQueueType;
    private final QueueProviderType msgQueueType;

    // 构造函数，直接传入项目-任务Map
    public TaskRepositoryManager(ConcurrentMap<Project, MessageMap<String, TaskEntity>> projectTaskMap, QueueProviderType queueType) {
        this(projectTaskMap, queueType, queueType);
    }

    public TaskRepositoryManager(ConcurrentMap<Project, MessageMap<String, TaskEntity>> projectTaskMap, QueueProviderType seedQueueType, QueueProviderType msgQueueType) {
        this.projectTaskMap.putAll(Objects.requireNonNull(projectTaskMap));
        this.seedQueueType = Objects.requireNonNull(seedQueueType);
        this.msgQueueType = Objects.requireNonNull(msgQueueType);
    }

    // 任务实体操作
    public void saveTask(Project project, TaskEntity taskEntity) {
        projectTaskMap.computeIfAbsent(project, k -> new com.xa.mass.base.channel.queue.memory.InMemoryMessageMap<>())
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

    public int getTotalTaskCount(Project project) {
        MessageMap<String, TaskEntity> map = projectTaskMap.get(project);
        return map != null ? map.size() : 0;
    }

    public int getTotalProjectCount() {
        return projectTaskMap.size();
    }

    // 种子队列操作
    public void createSeedQueue(String taskId) {
        taskSeedsMap.put(taskId, MessageQueueProviderRegistry.createQueue(seedQueueType, "xa_mass_platform::seed-" + taskId));
    }

    public void addSeed(String taskId, String seed) {
        taskSeedsMap.get(taskId).offer(seed);
    }

    public void addSeeds(String taskId, String[] seeds) {
        MessageQueue<String> queue = taskSeedsMap.get(taskId);
        for (String seed : seeds) {
            queue.offer(seed);
        }
    }

    public String pollSeed(String taskId) {
        try {
            return taskSeedsMap.get(taskId).poll(0, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public int getSeedCount(String taskId) {
        MessageQueue<String> queue = taskSeedsMap.get(taskId);
        return queue != null ? queue.size() : 0;
    }

    // 消息队列操作
    public void createMsgQueue(String taskId) {
        taskMsgMap.put(taskId, MessageQueueProviderRegistry.createQueue(msgQueueType, "xa_mass_platform::msg-" + taskId));
    }

    public void addMsg(String taskId, TaskMsgEntity taskMsg) {
        taskMsgMap.get(taskId).offer(taskMsg);
    }

    public TaskMsgEntity pollMsg(String taskId) {
        try {
            return taskMsgMap.get(taskId).poll(0, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public int getMsgCount(String taskId) {
        MessageQueue<TaskMsgEntity> queue = taskMsgMap.get(taskId);
        return queue != null ? queue.size() : 0;
    }
}
