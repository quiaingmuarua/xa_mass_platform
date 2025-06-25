package com.xa.mass.engine.monkey.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 混沌事件基础接口
 * 所有monkey事件都应该实现此接口
 */
public interface ChaosEvent {
    
    /**
     * 获取事件ID
     */
    String getEventId();
    
    /**
     * 获取事件类型
     */
    ChaosEventType getEventType();
    
    /**
     * 获取事件发生时间
     */
    Instant getTimestamp();
    
    /**
     * 获取事件严重程度
     */
    ChaosEventSeverity getSeverity();
    
    /**
     * 获取事件元数据
     */
    Map<String, Object> getMetadata();
    
    /**
     * 获取事件描述
     */
    String getDescription();
    
    /**
     * 事件类型枚举
     */
    enum ChaosEventType {
        // 任务审核相关
        TASK_REVIEW_RANDOM("任务随机审核"),
        TASK_REVIEW_DELAY("任务延迟审核"),
        TASK_REVIEW_PARTIAL_FAILURE("任务部分失败"),
        TASK_REVIEW_MISAPPROVAL("任务误通过"),
        
        // 设备状态相关
        DEVICE_OFFLINE_BATCH("设备批量下线"),
        DEVICE_OFFLINE_SINGLE("设备单个下线"),
        DEVICE_FLASH_DISCONNECT("设备闪断"),
        DEVICE_LONG_ABSENCE("设备长时间不归队"),
        DEVICE_ONLINE_BATCH("设备批量上线"),
        
        // Token/消息异常
        TOKEN_INVALIDATION("Token失效"),
        TOKEN_RETRY_LOOP("Token反复重试"),
        TOKEN_BATCH_UNAVAILABLE("Token批量不可用"),
        MESSAGE_PROCESSING_ERROR("消息处理异常"),
        
        // 任务分配冲突
        TASK_ASSIGNMENT_CONFLICT("任务分配冲突"),
        MESSAGE_DUPLICATE_ASSIGNMENT("消息重复分配"),
        BATCH_ORDER_CHAOS("批次乱序"),
        
        // 网络/链路异常
        RPC_TIMEOUT("RPC超时"),
        MESSAGE_QUEUE_BLOCK("消息队列堵塞"),
        NETWORK_LATENCY("网络延迟"),
        
        // 监控/归因失效
        LOGGING_FAILURE("日志打点异常"),
        STATUS_REPORT_FAILURE("状态回报异常"),
        METRICS_COLLECTION_FAILURE("指标收集异常");
        
        private final String description;
        
        ChaosEventType(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    /**
     * 事件严重程度枚举
     */
    enum ChaosEventSeverity {
        LOW("低", 1),
        MEDIUM("中", 2),
        HIGH("高", 3),
        CRITICAL("严重", 4);
        
        private final String description;
        private final int level;
        
        ChaosEventSeverity(String description, int level) {
            this.description = description;
            this.level = level;
        }
        
        public String getDescription() {
            return description;
        }
        
        public int getLevel() {
            return level;
        }
    }
    
    /**
     * 事件基础实现
     */
    abstract class BaseChaosEvent implements ChaosEvent {
        private final String eventId;
        private final Instant timestamp;
        private final ChaosEventType eventType;
        private final ChaosEventSeverity severity;
        private final Map<String, Object> metadata;
        private final String description;
        
        protected BaseChaosEvent(ChaosEventType eventType, ChaosEventSeverity severity, 
                               Map<String, Object> metadata, String description) {
            this.eventId = UUID.randomUUID().toString();
            this.timestamp = Instant.now();
            this.eventType = eventType;
            this.severity = severity;
            this.metadata = metadata;
            this.description = description;
        }
        
        @Override
        public String getEventId() {
            return eventId;
        }
        
        @Override
        public Instant getTimestamp() {
            return timestamp;
        }
        
        @Override
        public ChaosEventType getEventType() {
            return eventType;
        }
        
        @Override
        public ChaosEventSeverity getSeverity() {
            return severity;
        }
        
        @Override
        public Map<String, Object> getMetadata() {
            return metadata;
        }
        
        @Override
        public String getDescription() {
            return description;
        }
        
        @Override
        public String toString() {
            return String.format("ChaosEvent{eventId='%s', type=%s, severity=%s, description='%s', timestamp=%s}",
                    eventId, eventType, severity, description, timestamp);
        }
    }
} 