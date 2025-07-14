package com.xa.mass.base.channel.queue.api;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 消息流接口
 * 支持消息的投递、消费、确认(ACK)和认领(CLAIM)功能
 * 适用于需要可靠消息处理的场景
 * 
 * @param <T> 消息类型
 */
public interface MessageStream<T> {
    
    /**
     * 投递消息到流中
     * @param message 消息内容
     * @return 消息ID，用于后续的ACK或CLAIM操作
     */
    String offer(T message);
    
    /**
     * 从流中消费消息
     * @param timeout 等待时间
     * @param unit 时间单位
     * @return 消息包装对象，包含消息ID和内容
     * @throws InterruptedException 如果等待时线程被中断
     */
    StreamMessage<T> poll(long timeout, TimeUnit unit) throws InterruptedException;
    
    /**
     * 批量拉取消息
     * @param batchSize 批量数量
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return 消息列表
     * @throws InterruptedException 如果线程被中断
     */
    List<StreamMessage<T>> pollBatch(int batchSize, long timeout, TimeUnit unit) throws InterruptedException;
    
    /**
     * 确认消息已被成功处理
     * @param messageId 消息ID
     * @return 是否确认成功
     */
    boolean ack(String messageId);
    
    /**
     * 认领消息（重新分配）
     * @param messageId 消息ID
     * @param newTimeout 新的超时时间
     * @param unit 时间单位
     * @return 是否认领成功
     */
    boolean claim(String messageId, long newTimeout, TimeUnit unit);
    
    /**
     * 批量确认消息已被成功处理
     * @param messageIds 消息ID列表
     * @return 成功确认的消息数量
     */
    int ackBatch(List<String> messageIds);
    
    /**
     * 获取流的统计信息
     * @return 流统计信息
     */
    StreamStats getStats();
    
    /**
     * 获取流中待处理的消息数量
     * @return 消息数量
     */
    int size();
    
    /**
     * 获取流中正在处理的消息数量
     * @return 正在处理的消息数量
     */
    int processingSize();
    
    /**
     * 检查流是否为空
     * @return 是否为空
     */
    boolean isEmpty();
    
    /**
     * 获取流的名称
     * @return 流名称
     */
    String getName();
    
    /**
     * 清理过期的未确认消息
     * @return 清理的消息数量
     */
    int cleanupExpiredMessages();
    
    /**
     * 统一的构造方法接口
     * 所有实现类都应该提供这个构造方法
     * @param queueKey 流键名
     * @param messageType 消息类型Class
     * @param extraParams 扩展参数（可选，包含group、consumerName等）
     */
    // 注意：接口中不能定义构造方法，这里只是文档说明
    // 所有实现类都应该提供：MessageStream(String queueKey, Class<T> messageType, Map<String, String> extraParams)
    
    /**
     * 消息包装类，包含消息ID和内容
     * @param <T> 消息类型
     */
    class StreamMessage<T> {
        private final String messageId;
        private final T message;
        private final long timestamp;
        
        public StreamMessage(String messageId, T message) {
            this.messageId = messageId;
            this.message = message;
            this.timestamp = System.currentTimeMillis();
        }
        
        public String getMessageId() {
            return messageId;
        }
        
        public T getMessage() {
            return message;
        }
        
        public long getTimestamp() {
            return timestamp;
        }
        
        @Override
        public String toString() {
            return "StreamMessage{messageId='" + messageId + "', message=" + message + ", timestamp=" + timestamp + "}";
        }
    }
    
    /**
     * 流统计信息
     */
    class StreamStats {
        private final int totalSize;
        private final int pendingSize;
        private final int processingSize;
        private final String streamName;
        private final long timestamp;
        
        public StreamStats(int totalSize, int pendingSize, int processingSize, String streamName) {
            this.totalSize = totalSize;
            this.pendingSize = pendingSize;
            this.processingSize = processingSize;
            this.streamName = streamName;
            this.timestamp = System.currentTimeMillis();
        }
        
        public int getTotalSize() {
            return totalSize;
        }
        
        public int getPendingSize() {
            return pendingSize;
        }
        
        public int getProcessingSize() {
            return processingSize;
        }
        
        public String getStreamName() {
            return streamName;
        }
        
        public long getTimestamp() {
            return timestamp;
        }
        
        @Override
        public String toString() {
            return "StreamStats{totalSize=" + totalSize + ", pendingSize=" + pendingSize + 
                   ", processingSize=" + processingSize + ", streamName='" + streamName + 
                   "', timestamp=" + timestamp + "}";
        }
    }
}
