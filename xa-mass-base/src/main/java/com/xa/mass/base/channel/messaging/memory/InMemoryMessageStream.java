package com.xa.mass.base.channel.messaging.memory;

import com.xa.mass.base.channel.messaging.api.MessageStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存消息流实现
 * 支持消息的投递、消费、确认(ACK)和认领(CLAIM)功能
 * 
 * @param <T> 消息类型
 */
public class InMemoryMessageStream<T> implements MessageStream<T> {
    private static final Logger log = LoggerFactory.getLogger(InMemoryMessageStream.class);
    
    // 待处理消息队列
    private final BlockingQueue<StreamMessage<T>> pendingQueue;
    
    // 正在处理的消息映射 (messageId -> StreamMessage)
    private final Map<String, StreamMessage<T>> processingMessages;
    
    // 消息超时时间（毫秒）
    private final long defaultTimeoutMs;
    
    // 流名称
    private final String name;
    
    // 消息ID计数器
    private final AtomicInteger messageIdCounter = new AtomicInteger(0);

    /**
     * 统一的构造方法
     * @param queueKey 流键名
     * @param messageType 消息类型Class
     * @param extraParams 扩展参数（可选，包含group、consumerName等）
     */
    public InMemoryMessageStream(String queueKey, Class<T> messageType, Map<String, String> extraParams) {
        this.pendingQueue = new LinkedBlockingQueue<>();
        this.processingMessages = new ConcurrentHashMap<>();
        this.name = queueKey != null ? queueKey : "InMemoryMessageStream";
        
        // 从扩展参数中获取超时时间，默认30秒
        long timeoutMs = 30000;
        if (extraParams != null && extraParams.containsKey("timeout")) {
            try {
                timeoutMs = Long.parseLong(extraParams.get("timeout"));
            } catch (NumberFormatException e) {
                log.warn("Invalid timeout value in extraParams, using default 30000ms");
            }
        }
        this.defaultTimeoutMs = timeoutMs;
        
        log.debug("Created InMemoryMessageStream: name={}, messageType={}, timeout={}ms", 
                 name, messageType.getSimpleName(), defaultTimeoutMs);
    }

    /**
     * 简化的构造方法（向后兼容）
     * @param queueKey 流键名
     * @param messageType 消息类型Class
     */
    public InMemoryMessageStream(String queueKey, Class<T> messageType) {
        this(queueKey, messageType, null);
    }
    
    @Override
    public String offer(T message) {
        if (message == null) {
            throw new IllegalArgumentException("Message cannot be null");
        }
        
        String messageId = generateMessageId();
        StreamMessage<T> streamMessage = new StreamMessage<>(messageId, message);
        
        if (!pendingQueue.offer(streamMessage)) {
            throw new RuntimeException("Failed to offer message to in-memory stream");
        }
        
        log.debug("Message offered to stream: messageId={}, message={}", messageId, message);
        return messageId;
    }
    
    @Override
    public StreamMessage<T> poll(long timeout, TimeUnit unit) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Thread interrupted");
        }
        
        StreamMessage<T> streamMessage = pendingQueue.poll(timeout, unit);
        if (streamMessage != null) {
            // 将消息标记为正在处理
            processingMessages.put(streamMessage.getMessageId(), streamMessage);
            log.debug("Message polled from stream: messageId={}", streamMessage.getMessageId());
        }
        
        return streamMessage;
    }
    
    @Override
    public boolean ack(String messageId) {
        if (messageId == null) {
            return false;
        }
        
        StreamMessage<T> removed = processingMessages.remove(messageId);
        if (removed != null) {
            log.debug("Message acknowledged: messageId={}", messageId);
            return true;
        }
        
        log.warn("Message not found for ack: messageId={}", messageId);
        return false;
    }
    
    @Override
    public boolean claim(String messageId, long newTimeout, TimeUnit unit) {
        if (messageId == null) {
            return false;
        }
        
        StreamMessage<T> streamMessage = processingMessages.remove(messageId);
        if (streamMessage != null) {
            // 重新投递到待处理队列
            if (pendingQueue.offer(streamMessage)) {
                log.debug("Message claimed: messageId={}, newTimeout={} {}", 
                         messageId, newTimeout, unit);
                return true;
            } else {
                // 如果重新投递失败，放回处理中状态
                processingMessages.put(messageId, streamMessage);
                log.error("Failed to re-queue claimed message: messageId={}", messageId);
                return false;
            }
        }
        
        log.warn("Message not found for claim: messageId={}", messageId);
        return false;
    }
    
    @Override
    public int size() {
        return pendingQueue.size();
    }
    
    @Override
    public int processingSize() {
        return processingMessages.size();
    }
    
    @Override
    public boolean isEmpty() {
        return pendingQueue.isEmpty() && processingMessages.isEmpty();
    }
    
    @Override
    public String getName() {
        return name;
    }
    
    @Override
    public int cleanupExpiredMessages() {
        long currentTime = System.currentTimeMillis();
        final int[] cleanedCount = {0};
        
        // 清理过期的处理中消息
        processingMessages.entrySet().removeIf(entry -> {
            StreamMessage<T> message = entry.getValue();
            long messageAge = currentTime - message.getTimestamp();
            
            if (messageAge > defaultTimeoutMs) {
                log.debug("Cleaning up expired message: messageId={}, age={}ms", 
                         entry.getKey(), messageAge);
                cleanedCount[0]++;
                return true;
            }
            return false;
        });
        
        if (cleanedCount[0] > 0) {
            log.info("Cleaned up {} expired messages from stream: {}", cleanedCount[0], name);
        }
        
        return cleanedCount[0];
    }
    
    @Override
    public List<StreamMessage<T>> pollBatch(int batchSize, long timeout, TimeUnit unit) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Thread interrupted");
        }
        List<StreamMessage<T>> result = new ArrayList<>(batchSize);
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        for (int i = 0; i < batchSize; i++) {
            long now = System.currentTimeMillis();
            long remain = deadline - now;
            if (remain <= 0 && result.isEmpty()) {
                break;
            }
            StreamMessage<T> msg = (remain > 0)
                ? pendingQueue.poll(remain, TimeUnit.MILLISECONDS)
                : pendingQueue.poll();
            if (msg != null) {
                processingMessages.put(msg.getMessageId(), msg);
                result.add(msg);
            } else {
                break;
            }
        }
        return result;
    }
    
    @Override
    public int ackBatch(List<String> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return 0;
        }
        
        int ackCount = 0;
        for (String messageId : messageIds) {
            if (ack(messageId)) {
                ackCount++;
            }
        }
        
        log.debug("Batch acknowledged {} messages from {} total", ackCount, messageIds.size());
        return ackCount;
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
    
    private String generateMessageId() {
        return name + ":" + messageIdCounter.incrementAndGet() + ":" + UUID.randomUUID().toString().substring(0, 8);
    }
    
    // 测试和调试用的方法
    public Map<String, StreamMessage<T>> getProcessingMessages() {
        return new ConcurrentHashMap<>(processingMessages);
    }
    
    public int forceCleanupAllProcessing() {
        int count = processingMessages.size();
        processingMessages.clear();
        log.info("Force cleaned up {} processing messages from stream: {}", count, name);
        return count;
    }
} 