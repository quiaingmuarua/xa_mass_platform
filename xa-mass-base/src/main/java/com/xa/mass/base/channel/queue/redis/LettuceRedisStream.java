package com.xa.mass.base.channel.queue.redis;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xa.mass.base.channel.queue.api.MessageStream;
import io.lettuce.core.Consumer;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class LettuceRedisStream<T> implements MessageStream<T> {
    private static final Logger log = LoggerFactory.getLogger(LettuceRedisStream.class);
    
    // 默认消费者组和消费者名称
    private static final String DEFAULT_GROUP = "default-group";
    private static final String DEFAULT_CONSUMER = "default-consumer";

    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final String streamKey;
    private final String name;
    private final Gson gson;
    private final Class<T> messageType;

    // 消费组名和消费者名
    private final String group;
    private final String consumerName;

    /**
     * 创建Redis Stream实例（使用默认消费者组和消费者）
     * @param streamKey Redis Stream的键名
     * @param name 流名称
     * @param messageType 消息类型
     */
    public LettuceRedisStream(String streamKey, String name, Class<T> messageType) {
        this(streamKey, name, messageType, DEFAULT_GROUP, DEFAULT_CONSUMER);
    }

    /**
     * 创建Redis Stream实例
     * @param streamKey Redis Stream的键名
     * @param name 流名称
     * @param messageType 消息类型
     * @param group 消费组名
     * @param consumerName 消费者名
     */
    public LettuceRedisStream(String streamKey, String name, Class<T> messageType, String group, String consumerName) {
        this.connection = RedisConnectionManager.getConnection();
        this.commands = connection.sync();
        this.streamKey = streamKey + "::stream";
        this.name = name + "::stream";
        this.messageType = messageType;
        this.group = group != null ? group : DEFAULT_GROUP;
        this.consumerName = consumerName != null ? consumerName : DEFAULT_CONSUMER;
        this.gson = new GsonBuilder().create();

        // 确保消费者组存在
        ensureConsumerGroup();
    }

    @Override
    public String offer(T message) {
        if (message == null) {
            throw new IllegalArgumentException("Message cannot be null");
        }
        String jsonMessage = gson.toJson(message);
        String streamId = commands.xadd(streamKey, "data", jsonMessage);
        log.debug("Message offered to Redis stream: streamId={}, message={}", streamId, message);
        return streamId;
    }

    /**
     * 拉取新消息（仅获取未消费的新消息），支持阻塞timeout
     */
    @Override
    public StreamMessage<T> poll(long timeout, TimeUnit unit) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Thread interrupted");
        }
        long timeoutMs = unit.toMillis(timeout);

        List<io.lettuce.core.StreamMessage<String, String>> messages = commands.xreadgroup(
                Consumer.from(group, consumerName),
                XReadArgs.Builder.block(timeoutMs).count(1),
                XReadArgs.StreamOffset.from(streamKey, ">")
        );

        if (messages != null && !messages.isEmpty()) {
            // 解析Redis Stream消息格式
            List<Object> streamData = (List<Object>) messages.get(0);
            List<Object> messageList = (List<Object>) streamData.get(1);
            
            if (!messageList.isEmpty()) {
                List<Object> messageData = (List<Object>) messageList.get(0);
                String messageId = (String) messageData.get(0);
                List<Object> fields = (List<Object>) messageData.get(1);
                
                // 查找data字段
                String jsonMessage = null;
                for (int i = 0; i < fields.size(); i += 2) {
                    if ("data".equals(fields.get(i))) {
                        jsonMessage = (String) fields.get(i + 1);
                        break;
                    }
                }
                
                if (jsonMessage != null) {
                    T message = gson.fromJson(jsonMessage, messageType);
                    log.debug("Message polled from Redis stream: messageId={}", messageId);
                    return new StreamMessage<>(messageId, message);
                }
            }
        }
        return null;
    }

    @Override
    public List<StreamMessage<T>> pollBatch(int batchSize, long timeout, TimeUnit unit) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Thread interrupted");
        }
        long timeoutMs = unit.toMillis(timeout);
        List<io.lettuce.core.StreamMessage<String, String>> redisMsgs = commands.xreadgroup(
                Consumer.from(group, consumerName),
                XReadArgs.Builder.block(timeoutMs).count(batchSize),
                XReadArgs.StreamOffset.from(streamKey, ">")
        );
        List<StreamMessage<T>> result = new ArrayList<>();
        if (redisMsgs != null) {
            for (io.lettuce.core.StreamMessage<String, String> redisMsg : redisMsgs) {
                String messageId = redisMsg.getId();
                String jsonMessage = redisMsg.getBody().get("data");
                if (jsonMessage != null) {
                    T message = gson.fromJson(jsonMessage, messageType);
                    result.add(new StreamMessage<>(messageId, message));
                }
            }
        }
        return result;
    }

    @Override
    public boolean ack(String messageId) {
        if (messageId == null) {
            return false;
        }
        try {
            Long ackCount = commands.xack(streamKey, group, messageId);
            boolean success = ackCount != null && ackCount > 0;
            if (success) {
                log.debug("Message acknowledged: messageId={}", messageId);
            } else {
                log.warn("Message not found for ack: messageId={}", messageId);
            }
            return success;
        } catch (Exception e) {
            log.error("Failed to ack message: messageId={}", messageId, e);
            return false;
        }
    }

    @Override
    public boolean claim(String messageId, long minIdleTime, TimeUnit unit) {
        // 推荐使用 XAUTOCLAIM/XCLAIM 处理超时pending消息
        // 可补充 claim 实现（如有需要）
        // 此处简化为不实现
        log.warn("Claim operation not implemented in this version.");
        return false;
    }

    @Override
    public int size() {
        try {
            Long size = commands.xlen(streamKey);
            return size != null ? size.intValue() : 0;
        } catch (Exception e) {
            log.error("Failed to get stream size: {}", streamKey, e);
            return 0;
        }
    }

    @Override
    public int processingSize() {
        try {
            // 查询 pending 条数（所有消费者pending消息总数）
            Object pending = commands.xpending(streamKey, group);
            if (pending != null) {
                // 解析pending信息
                if (pending instanceof List) {
                    List<?> pendingList = (List<?>) pending;
                    if (!pendingList.isEmpty()) {
                        return ((Long) pendingList.get(0)).intValue();
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to get processingSize for stream: {}", streamKey, e);
        }
        return 0;
    }

    @Override
    public boolean isEmpty() {
        return size() == 0 && processingSize() == 0;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public int cleanupExpiredMessages() {
        // 复杂场景可用 XTRIM/MAXLEN 或定期批量 XDEL 已确认的消息，简单版此处略
        return 0;
    }
    
    /**
     * 确保消费者组存在
     */
    private void ensureConsumerGroup() {
        try {
            // 尝试创建消费者组，如果已存在则忽略错误
            commands.xgroupCreate(XReadArgs.StreamOffset.from(streamKey, "$"), group);
            log.debug("Consumer group created or already exists: {}", group);
        } catch (Exception e) {
            // 消费者组可能已存在，这是正常的
            log.debug("Consumer group may already exist: {}", group);
        }
    }
    
    /**
     * 获取Stream键名
     */
    public String getStreamKey() {
        return streamKey;
    }
    
    /**
     * 获取消费者组名称
     */
    public String getGroup() {
        return group;
    }
    
    /**
     * 获取消费者名称
     */
    public String getConsumerName() {
        return consumerName;
    }
}
