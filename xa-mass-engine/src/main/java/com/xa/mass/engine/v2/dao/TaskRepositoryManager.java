package com.xa.mass.engine.v2.dao;

import com.xa.mass.base.channel.queue.MessageQueueProviderRegistry;
import com.xa.mass.base.channel.queue.QueueProviderType;
import com.xa.mass.base.channel.queue.api.MessageMap;
import com.xa.mass.base.channel.queue.api.MessageQueue;
import com.xa.mass.engine.v2.entity.TaskEntity;
import com.xa.mass.engine.v2.entity.TaskMsgEntity;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 任务仓库管理器 - 纯数据操作层
 */
public class TaskRepositoryManager {

    private final ConcurrentMap<String, MessageQueue<String>> taskSeedsMap = new ConcurrentHashMap<>();
    private final MessageMap<String, TaskEntity> taskMap;
    private final ConcurrentMap<String, MessageQueue<TaskMsgEntity>> taskMsgMap = new ConcurrentHashMap<>();
    private final QueueProviderType seedQueueType;
    private final QueueProviderType msgQueueType;

    public TaskRepositoryManager(MessageMap<String, TaskEntity> taskMap, QueueProviderType queueType) {
        this(taskMap, queueType, queueType);
    }

    public TaskRepositoryManager(MessageMap<String, TaskEntity> taskMap, QueueProviderType seedQueueType, QueueProviderType msgQueueType) {
        this.taskMap = Objects.requireNonNull(taskMap);
        this.seedQueueType = Objects.requireNonNull(seedQueueType);
        this.msgQueueType = Objects.requireNonNull(msgQueueType);
    }

    // 任务实体操作
    public void saveTask(TaskEntity taskEntity) {
        taskMap.put(taskEntity.getTaskId(), taskEntity);
    }

    public TaskEntity getTask(String taskId) {
        return taskMap.get(taskId);
    }

    public boolean containsTask(String taskId) {
        return taskMap.containsKey(taskId);
    }

    public int getTotalTaskCount() {
        return taskMap.size();
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
