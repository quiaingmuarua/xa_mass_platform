package com.xa.mass.core.server.queue;

import com.google.gson.Gson;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisStreamCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RedisStreamMessageQueue implements MessageQueue<StoredMessage>, DisposableBean {
    private static final Logger logger = LoggerFactory.getLogger(RedisStreamMessageQueue.class);

    private final String streamKey;
    private final String groupName;
    private final String consumerName;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisStreamCommands<String, String> streamCommands;
    private final Gson gson;
    private static final String MESSAGE_PAYLOAD_FIELD = "payload";
    private volatile boolean active = true; // 控制轮询循环

    public RedisStreamMessageQueue(
            String streamKey,
            String groupName,
            String consumerName,
            StatefulRedisConnection<String, String> connection,
            Gson gson) {
        this.streamKey = streamKey;
        this.groupName = groupName;
        this.consumerName = consumerName;
        this.connection = connection;
        this.streamCommands = this.connection.sync(); // 使用同步命令
        this.gson = gson;
        initializeGroup();
    }

    private void initializeGroup() {
        try {
            // XGROUP CREATE mystream mygroup 0 MKSTREAM
            // MKSTREAM 选项确保如果流不存在则创建它
            String result = streamCommands.xgroupCreate(
                    XReadArgs.StreamOffset.from(streamKey, "0-0"), // 从流的开始创建组
                    groupName,
                    XGroupCreateArgs.Builder.mkstream(true) // 如果流不存在则创
            );
            logger.info("Consumer group '{}' on stream '{}' creation result: {}", groupName, streamKey, result);
        } catch (RedisBusyException e) {
            // RedisBusyException: BUSYGROUP Consumer Group name already exists
            logger.info("Consumer group '{}' already exists for stream '{}'.", groupName, streamKey);
        } catch (Exception e) {
            logger.error("Failed to create or verify consumer group '{}' for stream '{}'", groupName, streamKey, e);
            // 根据严重性，可能需要重新抛出异常或阻止应用启动
        }
    }

    @Override
    public void offer(StoredMessage message) {
        if (!active) {
            logger.warn("Queue {} is not active, cannot offer message.", streamKey);
            return;
        }
        if (message == null) {
            throw new IllegalArgumentException("Message cannot be null");
        }
        try {
            String jsonPayload = gson.toJson(message);
            Map<String, String> body = Collections.singletonMap(MESSAGE_PAYLOAD_FIELD, jsonPayload);
            // XADD mystream * field1 value1 field2 value2
            String messageId = streamCommands.xadd(streamKey, body);
            logger.debug("Offered message ID {} to stream '{}'", messageId, streamKey);
        } catch (Exception e) {
            logger.error("Failed to offer message to Redis stream '{}'", streamKey, e);
            throw new RuntimeException("Failed to offer message to Redis stream", e);
        }
    }

    @Override
    public StoredMessage poll(long timeout, TimeUnit unit) throws InterruptedException {
        if (!active) {
            logger.warn("Queue {} is not active, poll operation skipped.", streamKey);
            // 如果队列已关闭，快速返回null或抛出特定异
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            return null;
        }
        long timeoutMillis = unit.toMillis(timeout);
        try {
            // XREADGROUP GROUP mygroup consumer1 COUNT 1 BLOCK <timeoutMillis> STREAMS mystream >
            List<StreamMessage<String, String>> messages = streamCommands.xreadgroup(
                    Consumer.from(groupName, consumerName),
                    XReadArgs.Builder.count(1).block(timeoutMillis),
                    XReadArgs.StreamOffset.lastConsumed(streamKey) // '>' 表示只读取新消息
            );

            if (messages != null && !messages.isEmpty()) {
                StreamMessage<String, String> streamMessage = messages.get(0);
                String jsonPayload = streamMessage.getBody().get(MESSAGE_PAYLOAD_FIELD);

                if (jsonPayload == null) {
                    logger.warn("Polled message ID {} from stream '{}' has null payload. Acknowledging to remove.", streamMessage.getId(), streamKey);
                    streamCommands.xack(streamKey, groupName, streamMessage.getId()); // 确认空消息以移除
                    return null;
                }

                try {
                    StoredMessage deserializedMessage = gson.fromJson(jsonPayload, StoredMessage.class);
                    // 成功反序列化后再确认消息
                    streamCommands.xack(streamKey, groupName, streamMessage.getId());
                    logger.debug("Polled and ACKed message ID {} from stream '{}'", streamMessage.getId(), streamKey);
                    return deserializedMessage;
                } catch (Exception e) {
                    logger.error("Failed to deserialize message payload for ID {}: {}. Message will be re-delivered or become pending.", streamMessage.getId(), jsonPayload, e);
                    // ACK，消息会保留在流中，并可能在超时后被重新传递给其他消费者或当前消费
                    // 考虑死信队列 (DLQ) 策略
                    return null;
                }
            }
        } catch (RedisCommandTimeoutException e) {
            // 这是预期的行为，当在指定block 时间内没有消息时
            logger.trace("Timeout while polling stream '{}', group '{}'", streamKey, groupName);
            return null;
        } catch (RedisException e) {
            // 处理其他 Redis 异常
            logger.error("Redis exception while polling stream '{}', group '{}'", streamKey, groupName, e);
            // 如果线程被中断，则抛InterruptedException
            if (Thread.interrupted()) { // 检查并清除中断状
                throw new InterruptedException("Polling was interrupted due to RedisException");
            }
            // 对于其他 Redis 错误，可能需要短暂休眠以避免快速循
            try {
                TimeUnit.MILLISECONDS.sleep(100); // 短暂休眠
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie; // 重新抛出中断异常
            }
            return null;
        }
        return null; // 在超时时间内没有收到消息
    }


    @Override
    public boolean isEmpty() {
        try {
            // XLEN 返回流中的消息总数。对于消费者来说，"意味着没有待处理的新消息
            // 一个更准确的检查是尝试非阻塞地 poll 一次
            // 或者检XINFO GROUPS <stream_key> <group_name> 中的 pending lag
            return streamCommands.xlen(streamKey) == 0; // 简化版：如果流中没有消息，则为
        } catch (Exception e) {
            logger.error("Failed to check if stream '{}' is empty", streamKey, e);
            return true; // 出错时保守地认为为空
        }
    }

    @Override
    public int size() {
        try {
            Long len = streamCommands.xlen(streamKey);
            return len != null ? len.intValue() : 0;
        } catch (Exception e) {
            logger.error("Failed to get size of stream '{}'", streamKey, e);
            return 0;
        }
    }

    @Override
    public void destroy() {
        logger.info("Shutting down RedisStreamMessageQueue for stream: {}", streamKey);
        this.active = false;
        // Lettuce 连接通常RedisClient Spring 管理其生命周期，
        // 这里不需要显式关connection，除非这个队列独占它
    }
}
