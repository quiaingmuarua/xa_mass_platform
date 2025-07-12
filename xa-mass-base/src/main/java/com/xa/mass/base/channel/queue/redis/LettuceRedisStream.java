package com.xa.mass.base.channel.queue.redis;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xa.mass.base.channel.queue.api.MessageStream;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于Lettuce的Redis Stream实现（简化版本）
 * 支持消息的投递、消费、确认(ACK)和认领(CLAIM)功能
 * 
 * @param <T> 消息类型
 */
public class LettuceRedisStream<T> implements MessageStream<T> {
    private static final Logger log = LoggerFactory.getLogger(LettuceRedisStream.class);
    
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final String streamKey;
    private final String name;
    private final Gson gson;
    private final Class<T> messageType;
    
    // 消息ID计数器
    private final AtomicInteger messageIdCounter = new AtomicInteger(0);
    
    /**
     * 创建Redis Stream实例
     * @param streamKey Redis Stream的键名
     * @param name 流名称
     * @param messageType 消息类型
     */
    public LettuceRedisStream(String streamKey, String name, Class<T> messageType) {
        this.connection = RedisConnectionManager.getConnection();
        this.commands = connection.sync();
        this.streamKey = streamKey + "::stream";
        this.name = name + "::stream";
        this.messageType = messageType;
        this.gson = new GsonBuilder().create();
    }
    
    @Override
    public String offer(T message) {
        if (message == null) {
            throw new IllegalArgumentException("Message cannot be null");
        }
        
        try {
            String jsonMessage = gson.toJson(message);
            
            // 使用XADD命令添加消息到Stream，Redis会自动生成ID
            String streamId = commands.xadd(streamKey, "data", jsonMessage);
            
            log.debug("Message offered to Redis stream: streamId={}, message={}", 
                     streamId, message);
            
            return streamId;
        } catch (Exception e) {
            throw new RuntimeException("Failed to offer message to Redis stream: " + streamKey, e);
        }
    }
    
    @Override
    public StreamMessage<T> poll(long timeout, TimeUnit unit) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Thread interrupted");
        }
        
        try {
            long timeoutMs = unit.toMillis(timeout);
            
            // 使用XREAD命令读取消息（简化版本，不使用消费者组）
            List<io.lettuce.core.StreamMessage<String, String>> messages = commands.xread(
                io.lettuce.core.XReadArgs.Builder.block(timeoutMs).count(1),
                io.lettuce.core.XReadArgs.StreamOffset.latest(streamKey)
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
                        StreamMessage<T> streamMessage = new StreamMessage<>(messageId, message);
                        
                        log.debug("Message polled from Redis stream: messageId={}", messageId);
                        return streamMessage;
                    }
                }
            }
            
            return null;
        } catch (Exception e) {
            throw new RuntimeException("Failed to poll message from Redis stream: " + streamKey, e);
        }
    }
    
    @Override
    public boolean ack(String messageId) {
        if (messageId == null) {
            return false;
        }
        
        try {
            // 简化版本：直接删除消息作为ACK
            Long delCount = commands.xdel(streamKey, messageId);
            boolean success = delCount != null && delCount > 0;
            
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
    public boolean claim(String messageId, long newTimeout, TimeUnit unit) {
        // 简化版本：不支持CLAIM，直接返回false
        log.warn("Claim operation not supported in simplified Redis Stream implementation: messageId={}", messageId);
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
        // 简化版本：不支持处理中消息统计
        return 0;
    }
    
    @Override
    public boolean isEmpty() {
        return size() == 0;
    }
    
    @Override
    public String getName() {
        return name;
    }
    
    @Override
    public int cleanupExpiredMessages() {
        // 简化版本：不支持过期消息清理
        return 0;
    }
    
    /**
     * 获取Stream键名
     */
    public String getStreamKey() {
        return streamKey;
    }
} 