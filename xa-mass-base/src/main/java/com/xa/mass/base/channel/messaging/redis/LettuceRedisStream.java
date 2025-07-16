package com.xa.mass.base.channel.messaging.redis;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xa.mass.base.channel.messaging.api.MessageStream;
import com.xa.mass.base.tool.RedisConnectionManager;
import io.lettuce.core.Consumer;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LettuceRedisStream<T> implements MessageStream<T> {
    private static final Logger log = LoggerFactory.getLogger(LettuceRedisStream.class);

    // 默认消费者组和消费者名称
    private static final String DEFAULT_GROUP = "default-group";
    private static final String DEFAULT_CONSUMER = "default-consumer";

    private final RedisCommands<String, String> commands;
    private final String streamKey;
    private final String name;
    private final Gson gson;
    private final Class<T> messageType;

    // 消费组名和消费者名
    private final String group;
    private final String consumerName;

    /**
     * 统一的构造方法
     * @param queueKey 流键名
     * @param messageType 消息类型Class
     * @param extraParams 扩展参数（可选，包含group、consumerName等）
     */
    public LettuceRedisStream(String queueKey, Class<T> messageType, Map<String, String> extraParams) {
        StatefulRedisConnection<String, String> connection = RedisConnectionManager.getConnection();
        this.commands = connection.sync();
        this.streamKey = queueKey + "::stream";
        this.name = queueKey + "::stream";
        this.messageType = messageType;
        
        // 从扩展参数中获取group和consumerName，使用默认值
        this.group = extraParams != null ? extraParams.getOrDefault("group", DEFAULT_GROUP) : DEFAULT_GROUP;
        this.consumerName = extraParams != null ? extraParams.getOrDefault("consumerName", DEFAULT_CONSUMER) : DEFAULT_CONSUMER;
        
        this.gson = new GsonBuilder().create();
        
        log.debug("Created LettuceRedisStream: queueKey={}, messageType={}, group={}, consumer={}", 
                 queueKey, messageType.getSimpleName(), group, consumerName);
        
        ensureConsumerGroup();
    }

    /**
     * 简化的构造方法（向后兼容）
     * @param queueKey 流键名
     * @param messageType 消息类型Class
     */
    public LettuceRedisStream(String queueKey, Class<T> messageType) {
        this(queueKey, messageType, null);
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
            io.lettuce.core.StreamMessage<String, String> msg = messages.get(0);
            String messageId = msg.getId();
            String jsonMessage = msg.getBody().get("data");
            if (jsonMessage != null) {
                T message = gson.fromJson(jsonMessage, messageType);
                log.debug("Message polled from Redis stream: messageId={}", messageId);
                return new StreamMessage<>(messageId, message);
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
            Object pending = commands.xpending(streamKey, group);
            if (pending != null) {
                // 解析pending信息
                if (pending instanceof List<?> pendingList) {
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
        // 可补充定期trim，见之前建议
        return 0;
    }

    @Override
    public int ackBatch(List<String> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return 0;
        }

        try {
            // 批量确认消息
            Long ackCount = commands.xack(streamKey, group, messageIds.toArray(new String[0]));
            int successCount = ackCount != null ? ackCount.intValue() : 0;

            if (successCount > 0) {
                log.debug("Batch acknowledged {} messages from {} total", successCount, messageIds.size());
            } else {
                log.warn("No messages were acknowledged from batch of {}", messageIds.size());
            }

            return successCount;
        } catch (Exception e) {
            log.error("Failed to batch ack messages: {}", messageIds, e);
            return 0;
        }
    }

    @Override
    public StreamStats getStats() {
        return new StreamStats(
            size() + processingSize(),
            size(),
            processingSize(),
            name
        );
    }

    private void ensureConsumerGroup() {
        try {
            // 尝试创建消费者组，如果已存在则忽略错误
            commands.xgroupCreate(XReadArgs.StreamOffset.from(streamKey, "0"), group);
            log.debug("Consumer group created or already exists: group={}, stream={}", group, streamKey);
        } catch (Exception e) {
            // 消费者组已存在，这是正常的
            log.debug("Consumer group already exists: group={}, stream={}", group, streamKey);
        }
    }

    public String getStreamKey() {
        return streamKey;
    }

    public String getGroup() {
        return group;
    }

    public String getConsumerName() {
        return consumerName;
    }
}
