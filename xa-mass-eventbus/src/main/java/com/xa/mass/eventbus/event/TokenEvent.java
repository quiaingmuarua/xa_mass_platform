package com.xa.mass.eventbus.event;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Token和消息异常相关事件
 */
public class TokenEvent {
    
    /**
     * Token失效事件
     */
    public static class TokenInvalidationEvent extends ChaosEvent.BaseChaosEvent {
        private final String tokenId;
        private final String deviceId;
        private final String reason;
        private final long invalidationTime;
        
        public TokenInvalidationEvent(String tokenId, String deviceId, String reason, long invalidationTime) {
            super(ChaosEventType.TOKEN_INVALIDATION,
                  ChaosEventSeverity.MEDIUM,
                  createMetadata(tokenId, deviceId, reason, invalidationTime),
                  String.format("Token %s 失效，设备: %s，原因: %s", tokenId, deviceId, reason));
            this.tokenId = tokenId;
            this.deviceId = deviceId;
            this.reason = reason;
            this.invalidationTime = invalidationTime;
        }
        
        private static Map<String, Object> createMetadata(String tokenId, String deviceId, String reason, long invalidationTime) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("tokenId", tokenId);
            metadata.put("deviceId", deviceId);
            metadata.put("reason", reason);
            metadata.put("invalidationTime", invalidationTime);
            return metadata;
        }
        
        public String getTokenId() {
            return tokenId;
        }
        
        public String getDeviceId() {
            return deviceId;
        }
        
        public String getReason() {
            return reason;
        }
        
        public long getInvalidationTime() {
            return invalidationTime;
        }
    }
    
    /**
     * Token反复重试事件
     */
    public static class TokenRetryLoopEvent extends ChaosEvent.BaseChaosEvent {
        private final String tokenId;
        private final String deviceId;
        private final int retryCount;
        private final long retryIntervalMs;
        private final String lastError;
        
        public TokenRetryLoopEvent(String tokenId, String deviceId, int retryCount, long retryIntervalMs, String lastError) {
            super(ChaosEventType.TOKEN_RETRY_LOOP,
                  retryCount > 10 ? ChaosEventSeverity.HIGH : ChaosEventSeverity.MEDIUM,
                  createMetadata(tokenId, deviceId, retryCount, retryIntervalMs, lastError),
                  String.format("Token %s 反复重试，设备: %s，重试次数: %d，间隔: %dms", 
                              tokenId, deviceId, retryCount, retryIntervalMs));
            this.tokenId = tokenId;
            this.deviceId = deviceId;
            this.retryCount = retryCount;
            this.retryIntervalMs = retryIntervalMs;
            this.lastError = lastError;
        }
        
        private static Map<String, Object> createMetadata(String tokenId, String deviceId, int retryCount, long retryIntervalMs, String lastError) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("tokenId", tokenId);
            metadata.put("deviceId", deviceId);
            metadata.put("retryCount", retryCount);
            metadata.put("retryIntervalMs", retryIntervalMs);
            metadata.put("lastError", lastError);
            return metadata;
        }
        
        public String getTokenId() {
            return tokenId;
        }
        
        public String getDeviceId() {
            return deviceId;
        }
        
        public int getRetryCount() {
            return retryCount;
        }
        
        public long getRetryIntervalMs() {
            return retryIntervalMs;
        }
        
        public String getLastError() {
            return lastError;
        }
    }
    
    /**
     * Token批量不可用事件
     */
    public static class TokenBatchUnavailableEvent extends ChaosEvent.BaseChaosEvent {
        private final List<String> tokenIds;
        private final String reason;
        private final double unavailableRate;
        
        public TokenBatchUnavailableEvent(List<String> tokenIds, String reason, double unavailableRate) {
            super(ChaosEventType.TOKEN_BATCH_UNAVAILABLE,
                  unavailableRate > 0.7 ? ChaosEventSeverity.HIGH : ChaosEventSeverity.MEDIUM,
                  createMetadata(tokenIds, reason, unavailableRate),
                  String.format("Token批量不可用，Token数: %d，不可用率: %.2f%%，原因: %s", 
                              tokenIds.size(), unavailableRate * 100, reason));
            this.tokenIds = tokenIds;
            this.reason = reason;
            this.unavailableRate = unavailableRate;
        }
        
        private static Map<String, Object> createMetadata(List<String> tokenIds, String reason, double unavailableRate) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("tokenIds", tokenIds);
            metadata.put("reason", reason);
            metadata.put("unavailableRate", unavailableRate);
            metadata.put("tokenCount", tokenIds.size());
            return metadata;
        }
        
        public List<String> getTokenIds() {
            return tokenIds;
        }
        
        public String getReason() {
            return reason;
        }
        
        public double getUnavailableRate() {
            return unavailableRate;
        }
    }
    
    /**
     * 消息处理异常事件
     */
    public static class MessageProcessingErrorEvent extends ChaosEvent.BaseChaosEvent {
        private final String messageId;
        private final String taskId;
        private final String errorType;
        private final String errorMessage;
        private final int retryAttempts;
        
        public MessageProcessingErrorEvent(String messageId, String taskId, String errorType, String errorMessage, int retryAttempts) {
            super(ChaosEventType.MESSAGE_PROCESSING_ERROR,
                  retryAttempts > 3 ? ChaosEventSeverity.HIGH : ChaosEventSeverity.MEDIUM,
                  createMetadata(messageId, taskId, errorType, errorMessage, retryAttempts),
                  String.format("消息处理异常，消息ID: %s，任务ID: %s，错误类型: %s，重试次数: %d", 
                              messageId, taskId, errorType, retryAttempts));
            this.messageId = messageId;
            this.taskId = taskId;
            this.errorType = errorType;
            this.errorMessage = errorMessage;
            this.retryAttempts = retryAttempts;
        }
        
        private static Map<String, Object> createMetadata(String messageId, String taskId, String errorType, String errorMessage, int retryAttempts) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("messageId", messageId);
            metadata.put("taskId", taskId);
            metadata.put("errorType", errorType);
            metadata.put("errorMessage", errorMessage);
            metadata.put("retryAttempts", retryAttempts);
            return metadata;
        }
        
        public String getMessageId() {
            return messageId;
        }
        
        public String getTaskId() {
            return taskId;
        }
        
        public String getErrorType() {
            return errorType;
        }
        
        public String getErrorMessage() {
            return errorMessage;
        }
        
        public int getRetryAttempts() {
            return retryAttempts;
        }
    }
} 