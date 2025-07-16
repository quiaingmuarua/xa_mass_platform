package com.xa.mass.engine.v2.dao;

import com.xa.mass.base.channel.messaging.MessageMapProviderRegistry;
import com.xa.mass.base.channel.messaging.MessageProviderType;
import com.xa.mass.base.channel.messaging.MessageStreamProviderRegistry;
import com.xa.mass.base.channel.messaging.api.MessageMap;
import com.xa.mass.base.channel.messaging.api.MessageStream;
import com.xa.mass.base.enums.Project;
import com.xa.mass.engine.v2.entity.TaskEntity;
import com.xa.mass.engine.v2.entity.TaskMsgEntity;
import com.xa.mass.engine.v2.util.QueueKeyUtil;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 任务仓储管理器 v2
 * 支持项目隔离的任务存储和流管理
 */
public class TaskRepositoryManager {

    // 外层 key: project，内层 key: taskId
    private final ConcurrentMap<Project, MessageMap<String, TaskEntity>> projectTaskMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, MessageStream<String>> seedStreams = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, MessageStream<TaskMsgEntity>> msgStreams = new ConcurrentHashMap<>();
    private final MessageProviderType seedStreamType;
    private final MessageProviderType msgStreamType;

    // 构造函数，直接传入项目-任务Map
    public TaskRepositoryManager(MessageProviderType streamType) {
        this(streamType, streamType);
    }

    public TaskRepositoryManager(MessageProviderType seedStreamType, MessageProviderType msgStreamType) {
        this.seedStreamType = Objects.requireNonNull(seedStreamType);
        this.msgStreamType = Objects.requireNonNull(msgStreamType);
    }

    // 任务实体操作
    public void saveTask(Project project, TaskEntity taskEntity) {
        projectTaskMap.computeIfAbsent(project, (Project k) -> MessageMapProviderRegistry.createMap(seedStreamType, QueueKeyUtil.getProjectTaskStreamKey(taskEntity), TaskEntity.class))
        .put(taskEntity.getTaskId(), taskEntity);
    }

    public Collection<TaskEntity> getProjectTasks(Project project){
        MessageMap<String, TaskEntity> map = projectTaskMap.get(project);
       return  map.values();
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
     * 便捷构造器：使用默认的内存流为所有项目初始化
     */
    public static TaskRepositoryManager createWithDefaultProjects(MessageProviderType streamType) {
        return createWithDefaultProjects(streamType, streamType);
    }

    /**
     * 便捷构造器：使用指定的队列类型为所有项目初始化
     */
    public static TaskRepositoryManager createWithDefaultProjects(MessageProviderType seedStreamType, MessageProviderType msgStreamType) {
        TaskRepositoryManager manager = new TaskRepositoryManager(seedStreamType, msgStreamType);
        manager.registerAllProjects((Project project) -> {
            // 只初始化空的 MessageMap，不注册 project:all 队列名
            return MessageMapProviderRegistry.createMap(seedStreamType, QueueKeyUtil.getProjectAllTaskHashKey(project), TaskEntity.class);
        });
        return manager;
    }

    // 种子流操作
    public void createSeedStream(String seedStreamKey) {
        MessageStream<String> stream = MessageStreamProviderRegistry.createStream(
            seedStreamType, seedStreamKey, String.class, java.util.Collections.<String, String>emptyMap());
        seedStreams.put(seedStreamKey, stream);
    }

    public void addSeed(String seedStreamKey, String seed) {
        MessageStream<String> stream = seedStreams.get(seedStreamKey);
        if (stream != null) {
            stream.offer(seed);
        }
    }

    public String getSeed(String seedStreamKey) {
        MessageStream<String> stream = seedStreams.get(seedStreamKey);
        if (stream != null) {
            try {
                MessageStream.StreamMessage<String> message = stream.poll(0, TimeUnit.MILLISECONDS);
                if (message != null) {
                    return message.getMessage();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return null;
    }

    public int getSeedCount(String seedStreamKey) {
        MessageStream<String> stream = seedStreams.get(seedStreamKey);
        return stream != null ? stream.size() : 0;
    }

    // 消息流操作
    public void createMsgStream(String msgStreamKey) {
        MessageStream<TaskMsgEntity> stream = MessageStreamProviderRegistry.createStream(
            msgStreamType, msgStreamKey, TaskMsgEntity.class, java.util.Collections.<String, String>emptyMap());
        msgStreams.put(msgStreamKey, stream);
    }

    public void addMsg(String msgStreamKey, TaskMsgEntity msg) {
        MessageStream<TaskMsgEntity> stream = msgStreams.get(msgStreamKey);
        if (stream != null) {
            stream.offer(msg);
        }
    }

    public TaskMsgEntity getMsg(String msgStreamKey) {
        MessageStream<TaskMsgEntity> stream = msgStreams.get(msgStreamKey);
        if (stream != null) {
            try {
                MessageStream.StreamMessage<TaskMsgEntity> message = stream.poll(0, TimeUnit.MILLISECONDS);
                if (message != null) {
                    return message.getMessage();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return null;
    }

    public int getMsgCount(String msgStreamKey) {
        MessageStream<TaskMsgEntity> stream = msgStreams.get(msgStreamKey);
        return stream != null ? stream.size() : 0;
    }

    // 新增：批量操作支持
    public void addSeedsBatch(String seedStreamKey, List<String> seeds) {
        MessageStream<String> stream = seedStreams.get(seedStreamKey);
        if (stream != null) {
            for (String seed : seeds) {
                stream.offer(seed);
            }
        }
    }

    public List<String> getSeedsBatch(String seedStreamKey, int batchSize) {
        MessageStream<String> stream = seedStreams.get(seedStreamKey);
        if (stream != null) {
            try {
                List<MessageStream.StreamMessage<String>> messages = stream.pollBatch(batchSize, 0, TimeUnit.MILLISECONDS);
                return messages.stream()
                    .map(MessageStream.StreamMessage::getMessage)
                    .collect(Collectors.toList());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return Collections.emptyList();
    }

    public void addMsgsBatch(String msgStreamKey, List<TaskMsgEntity> msgs) {
        MessageStream<TaskMsgEntity> stream = msgStreams.get(msgStreamKey);
        if (stream != null) {
            for (TaskMsgEntity msg : msgs) {
                stream.offer(msg);
            }
        }
    }

    public List<TaskMsgEntity> getMsgsBatch(String msgStreamKey, int batchSize) {
        MessageStream<TaskMsgEntity> stream = msgStreams.get(msgStreamKey);
        if (stream != null) {
            try {
                List<MessageStream.StreamMessage<TaskMsgEntity>> messages = stream.pollBatch(batchSize, 0, TimeUnit.MILLISECONDS);
                return messages.stream()
                    .map(MessageStream.StreamMessage::getMessage)
                    .collect(Collectors.toList());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return Collections.emptyList();
    }

    // 新增：统计信息支持
    public MessageStream.StreamStats getSeedStreamStats(String seedStreamKey) {
        MessageStream<String> stream = seedStreams.get(seedStreamKey);
        return stream != null ? stream.getStats() : null;
    }

    public MessageStream.StreamStats getMsgStreamStats(String msgStreamKey) {
        MessageStream<TaskMsgEntity> stream = msgStreams.get(msgStreamKey);
        return stream != null ? stream.getStats() : null;
    }

    // 新增：清理过期消息
    public int cleanupExpiredSeeds(String seedStreamKey) {
        MessageStream<String> stream = seedStreams.get(seedStreamKey);
        return stream != null ? stream.cleanupExpiredMessages() : 0;
    }

    public int cleanupExpiredMsgs(String msgStreamKey) {
        MessageStream<TaskMsgEntity> stream = msgStreams.get(msgStreamKey);
        return stream != null ? stream.cleanupExpiredMessages() : 0;
    }
} 