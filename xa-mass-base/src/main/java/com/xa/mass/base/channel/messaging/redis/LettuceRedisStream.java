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

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 高性能、可插拔的 Redis Stream 消息流实现（Lettuce版）。
 * 支持单类型流、消费组、批量操作、自动 trim。
 *
 * @param <T> 消息类型（建议同一流内为单一类型）
 */
public class LettuceRedisStream<T> implements MessageStream<T> {
    private static final Logger log = LoggerFactory.getLogger(LettuceRedisStream.class);

    private static final String DEFAULT_GROUP = "default-group";
    private static final String DEFAULT_CONSUMER = "default-consumer";

    private final RedisCommands<String, String> commands;
    private final String streamKey;
    private final Class<T> messageType;
    private final Gson gson;
    private final String group;
    private final String consumerName;

    /**
     * 推荐构造：全参数
     */
    public LettuceRedisStream(String streamKey, Class<T> messageType, Map<String, String> extraParams, Gson gson) {
        StatefulRedisConnection<String, String> connection = RedisConnectionManager.getConnection();
        this.commands = connection.sync();
        this.streamKey = streamKey.endsWith("::stream") ? streamKey : streamKey + "::stream";
        this.messageType = Objects.requireNonNull(messageType);
        this.gson = gson != null ? gson : new GsonBuilder().create();

        this.group = extraParams != null ? extraParams.getOrDefault("group", DEFAULT_GROUP) : DEFAULT_GROUP;
        this.consumerName = extraParams != null ? extraParams.getOrDefault("consumerName", DEFAULT_CONSUMER) : DEFAULT_CONSUMER;

        log.debug("Created LettuceRedisStream: streamKey={}, messageType={}, group={}, consumer={}",
                this.streamKey, messageType.getSimpleName(), group, consumerName);
        ensureConsumerGroup();
    }

    /**
     * 兼容构造（可选外部 Gson）
     */
    public LettuceRedisStream(String streamKey, Class<T> messageType, Map<String, String> extraParams) {
        this(streamKey, messageType, extraParams, null);
    }
    public LettuceRedisStream(String streamKey, Class<T> messageType) {
        this(streamKey, messageType, null, null);
    }

    @Override
    public String offer(T message) {
        if (message == null) throw new IllegalArgumentException("Message cannot be null");
        String jsonMessage = gson.toJson(message);
        String streamId = commands.xadd(streamKey, Map.of("data", jsonMessage));
        log.debug("Offered message to Redis stream: streamId={}, type={}", streamId, messageType.getSimpleName());
        return streamId;
    }

    /**
     * 批量 offer
     */
    public List<String> offerBatch(List<T> messages) {
        if (messages == null || messages.isEmpty()) return Collections.emptyList();
        List<String> ids = new ArrayList<>(messages.size());
        for (T msg : messages) {
            ids.add(offer(msg));
        }
        return ids;
    }

    @Override
    public StreamMessage<T> poll(long timeout, TimeUnit unit) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Thread interrupted");
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
                try {
                    T message = gson.fromJson(jsonMessage, messageType);
                    log.debug("Polled message: messageId={}, streamKey={}", messageId, streamKey);
                    return new StreamMessage<>(messageId, message);
                } catch (Exception e) {
                    log.error("Failed to deserialize message: messageId={}, json={}", messageId, jsonMessage, e);
                }
            }
        }
        return null;
    }

    @Override
    public List<StreamMessage<T>> pollBatch(int batchSize, long timeout, TimeUnit unit) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Thread interrupted");
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
                    try {
                        T message = gson.fromJson(jsonMessage, messageType);
                        result.add(new StreamMessage<>(messageId, message));
                    } catch (Exception e) {
                        log.error("Failed to deserialize message in batch: messageId= {}, json= {}", messageId, e.getMessage());
                    }
                }
            }
        }
        return result;
    }

    @Override
    public boolean ack(String messageId) {
        if (messageId == null) return false;
        try {
            Long ackCount = commands.xack(streamKey, group, messageId);
            boolean success = ackCount != null && ackCount > 0;
            if (success) {
                log.debug("Acknowledged message: messageId={}", messageId);
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
    public int ackBatch(List<String> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) return 0;
        try {
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
            // Lettuce 6.x+ 支持 getCount()
            return Optional.ofNullable(commands.xpending(streamKey, group))
                    .map(info -> (int) info.getCount())
                    .orElse(0);
        } catch (Exception e) {
            log.error("Failed to get processingSize for stream: {}", streamKey, e);
            return 0;
        }
    }

    @Override
    public boolean isEmpty() {
        return size() == 0 && processingSize() == 0;
    }

    @Override
    public String getName() {
        return streamKey;
    }

    @Override
    public int cleanupExpiredMessages() {
        // 默认保留最新 100000 条，可参数化
        long maxLen = 100_000L;
        try {
            Long trimmed = commands.xtrim(streamKey, maxLen);
            log.info("Trimmed {} entries from Redis stream {}", trimmed, streamKey);
            return trimmed != null ? trimmed.intValue() : 0;
        } catch (Exception e) {
            log.error("Failed to trim stream: {}", streamKey, e);
            return 0;
        }
    }

    @Override
    public StreamStats getStats() {
        return new StreamStats(
                size() + processingSize(),
                size(),
                processingSize(),
                streamKey
        );
    }

    /**
     * 确保消费组存在（幂等，线程安全）
     */
    private void ensureConsumerGroup() {
        try {
            commands.xgroupCreate(XReadArgs.StreamOffset.from(streamKey, "0"), group,
                    io.lettuce.core.XGroupCreateArgs.Builder.mkstream(true));
            log.debug("Consumer group created: group={}, stream={}", group, streamKey);
        } catch (io.lettuce.core.RedisCommandExecutionException e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                // group 已存在，安全忽略
                log.debug("Consumer group already exists: group={}, stream={}", group, streamKey);
            } else {
                log.error("Failed to create consumer group: group={}, stream={}", group, streamKey, e);
                throw e;
            }
        }
    }

    /**
     * 清空整个stream（测试专用）
     */
    public void clear() {
        try {
            commands.del(streamKey);
            log.info("Cleared Redis stream: {}", streamKey);
        } catch (Exception e) {
            log.error("Failed to clear stream: {}", streamKey, e);
        }
    }

    // 便于单元测试/诊断
    public String getStreamKey() { return streamKey; }
    public String getGroup() { return group; }
    public String getConsumerName() { return consumerName; }
}
