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

    private final RedisCommands<String, String> commands;
    private final String streamKey;
    private final String name;
    private final Gson gson;
    private final Class<T> messageType;

    // 消费组名和消费者名
    private final String group;
    private final String consumerName;

    public LettuceRedisStream(String streamKey, String name, Class<T> messageType) {
        this(streamKey, name, messageType, DEFAULT_GROUP, DEFAULT_CONSUMER);
    }

    public LettuceRedisStream(String streamKey, String name, Class<T> messageType, String group, String consumerName) {
        StatefulRedisConnection<String, String> connection = RedisConnectionManager.getConnection();
        this.commands = connection.sync();
        this.streamKey = streamKey + "::stream";
        this.name = name + "::stream";
        this.messageType = messageType;
        this.group = group != null ? group : DEFAULT_GROUP;
        this.consumerName = consumerName != null ? consumerName : DEFAULT_CONSUMER;
        this.gson = new GsonBuilder().create();
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
        try {
            int totalSize = size();
            int processingSize = processingSize();
            int pendingSize = totalSize - processingSize; // 简化计算

            return new StreamStats(totalSize, processingSize, pendingSize, name);
        } catch (Exception e) {
            log.error("Failed to get stream stats for: {}", name, e);
            return new StreamStats(0, 0, 0, name);
        }
    }

    private void ensureConsumerGroup() {
        try {
            // 用 "0" 避免遗漏历史
            commands.xgroupCreate(XReadArgs.StreamOffset.from(streamKey, "0"), group);
            log.debug("Consumer group created or already exists: {}", group);
        } catch (Exception e) {
            log.debug("Consumer group may already exist: {}", group);
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
