package com.xa.mass.base.event;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务审核相关事件
 */
public class TaskReviewEvent {
    
    /**
     * 任务随机审核事件
     */
    public static class TaskReviewRandomEvent extends ChaosEvent.BaseChaosEvent {
        private final String taskId;
        private final double randomRate;
        
        public TaskReviewRandomEvent(String taskId, double randomRate) {
            super(ChaosEventType.TASK_REVIEW_RANDOM, 
                  randomRate > 0.8 ? ChaosEventSeverity.HIGH : ChaosEventSeverity.MEDIUM,
                  createMetadata(taskId, randomRate),
                  String.format("任务 %s 随机审核，通过率: %.2f%%", taskId, randomRate * 100));
            this.taskId = taskId;
            this.randomRate = randomRate;
        }
        
        private static Map<String, Object> createMetadata(String taskId, double randomRate) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("taskId", taskId);
            metadata.put("randomRate", randomRate);
            metadata.put("reviewType", "RANDOM");
            return metadata;
        }
        
        public String getTaskId() {
            return taskId;
        }
        
        public double getRandomRate() {
            return randomRate;
        }
    }
    
    /**
     * 任务延迟审核事件
     */
    public static class TaskReviewDelayEvent extends ChaosEvent.BaseChaosEvent {
        private final String taskId;
        private final long delayMs;
        
        public TaskReviewDelayEvent(String taskId, long delayMs) {
            super(ChaosEventType.TASK_REVIEW_DELAY,
                  delayMs > 30000 ? ChaosEventSeverity.HIGH : ChaosEventSeverity.MEDIUM,
                  createMetadata(taskId, delayMs),
                  String.format("任务 %s 延迟审核，延迟时间: %dms", taskId, delayMs));
            this.taskId = taskId;
            this.delayMs = delayMs;
        }
        
        private static Map<String, Object> createMetadata(String taskId, long delayMs) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("taskId", taskId);
            metadata.put("delayMs", delayMs);
            metadata.put("reviewType", "DELAY");
            return metadata;
        }
        
        public String getTaskId() {
            return taskId;
        }
        
        public long getDelayMs() {
            return delayMs;
        }
    }
    
    /**
     * 任务部分失败事件
     */
    public static class TaskReviewPartialFailureEvent extends ChaosEvent.BaseChaosEvent {
        private final String taskId;
        private final List<String> failedMessages;
        private final double failureRate;
        
        public TaskReviewPartialFailureEvent(String taskId, List<String> failedMessages, double failureRate) {
            super(ChaosEventType.TASK_REVIEW_PARTIAL_FAILURE,
                  failureRate > 0.5 ? ChaosEventSeverity.HIGH : ChaosEventSeverity.MEDIUM,
                  createMetadata(taskId, failedMessages, failureRate),
                  String.format("任务 %s 部分失败，失败率: %.2f%%，失败消息数: %d", 
                              taskId, failureRate * 100, failedMessages.size()));
            this.taskId = taskId;
            this.failedMessages = failedMessages;
            this.failureRate = failureRate;
        }
        
        private static Map<String, Object> createMetadata(String taskId, List<String> failedMessages, double failureRate) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("taskId", taskId);
            metadata.put("failedMessages", failedMessages);
            metadata.put("failureRate", failureRate);
            metadata.put("reviewType", "PARTIAL_FAILURE");
            return metadata;
        }
        
        public String getTaskId() {
            return taskId;
        }
        
        public List<String> getFailedMessages() {
            return failedMessages;
        }
        
        public double getFailureRate() {
            return failureRate;
        }
    }
    
    /**
     * 任务误通过事件
     */
    public static class TaskReviewMisapprovalEvent extends ChaosEvent.BaseChaosEvent {
        private final String taskId;
        private final String reason;
        private final List<String> misapprovedMessages;
        
        public TaskReviewMisapprovalEvent(String taskId, String reason, List<String> misapprovedMessages) {
            super(ChaosEventType.TASK_REVIEW_MISAPPROVAL,
                  ChaosEventSeverity.CRITICAL,
                  createMetadata(taskId, reason, misapprovedMessages),
                  String.format("任务 %s 误通过，原因: %s，误通过消息数: %d", 
                              taskId, reason, misapprovedMessages.size()));
            this.taskId = taskId;
            this.reason = reason;
            this.misapprovedMessages = misapprovedMessages;
        }
        
        private static Map<String, Object> createMetadata(String taskId, String reason, List<String> misapprovedMessages) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("taskId", taskId);
            metadata.put("reason", reason);
            metadata.put("misapprovedMessages", misapprovedMessages);
            metadata.put("reviewType", "MISAPPROVAL");
            return metadata;
        }
        
        public String getTaskId() {
            return taskId;
        }
        
        public String getReason() {
            return reason;
        }
        
        public List<String> getMisapprovedMessages() {
            return misapprovedMessages;
        }
    }
} 