package com.xa.mass.base.channel.eventbus.example;

import com.xa.mass.base.channel.eventbus.core.*;
import com.xa.mass.base.channel.messaging.api.MessageStream;
import com.xa.mass.base.channel.messaging.api.MessageStreamFactory;

import java.time.Instant;

/**
 * 优化后的EventBus示例，展示修复的性能和安全问题
 */
public class OptimizedEventBusExample {

    // 测试事件类型
    public static class PerformanceTestEvent implements MassEvent {
        private final String eventId;
        private final long processingTime;
        
        public PerformanceTestEvent(String eventId, long processingTime) {
            this.eventId = eventId;
            this.processingTime = processingTime;
        }
        
        public long getProcessingTime() { return processingTime; }
        
        @Override
        public String getEventId() { return eventId; }
        
        @Override
        public Instant getTimestamp() { return Instant.now(); }
        
        @Override
        public String getDescription() { return "Performance test event: " + eventId; }
        
        @Override
        public String toString() { 
            return "PerformanceTestEvent{id='" + eventId + "', processingTime=" + processingTime + "}"; 
        }
    }

    // 测试监听器 - 使用public方法（符合安全要求）
    public static class PerformanceTestListener {
        
        @MassSubscribe
        public void onPerformanceTest(PerformanceTestEvent event) {
            // 模拟处理时间
            if (event.getProcessingTime() > 0) {
                try {
                    Thread.sleep(event.getProcessingTime());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            System.out.println("[Listener] 处理事件: " + event.getEventId() + 
                " (耗时: " + event.getProcessingTime() + "ms)");
        }
        
        @MassSubscribe
        public void onAnotherEvent(String message) {
            System.out.println("[Listener] 字符串事件: " + message);
        }
    }

    // 错误的监听器示例 - 使用private方法（会被拒绝）
    public static class BadListener {
        @MassSubscribe
        @SuppressWarnings("unused")
        private void onBadEvent(PerformanceTestEvent event) {
            // 这个方法是private的，会被安全检查拒绝
            System.out.println("This should not be called");
        }
    }

    public static void main(String[] args) throws Exception {
        // 创建高性能配置
        EventBusConfig config = EventBusConfig.highThroughputConfig()
            .setBatchSize(5)  // 小批次，便于观察
            .setBatchTimeoutMs(500L)
            .setHandlerTimeoutSeconds(10L);
        
        System.out.println("=== 优化后的EventBus示例 ===");
        System.out.println("配置: " + config);
        
        // 创建优化的事件总线
        MessageStream<Object> stream = MessageStreamFactory.create("memory", "optimized-bus", Object.class);
        StreamEventBusFacade<Object> eventBus = new StreamEventBusFacade<>(stream, config);
        
        // 注册正确的监听器
        PerformanceTestListener listener = new PerformanceTestListener();
        eventBus.register(listener);
        
        // 尝试注册错误的监听器（会失败）
        try {
            BadListener badListener = new BadListener();
            eventBus.register(badListener);
        } catch (IllegalArgumentException e) {
            System.out.println("✅ 安全检查生效: " + e.getMessage());
        }
        
        // 发布测试事件
        System.out.println("\n--- 发布性能测试事件 ---");
        for (int i = 1; i <= 10; i++) {
            PerformanceTestEvent event = new PerformanceTestEvent("event-" + i, i % 3 * 100); // 0, 100, 200ms处理时间
            eventBus.post(event);
        }
        
        // 发布一些字符串事件
        eventBus.post("快速字符串事件 1");
        eventBus.post("快速字符串事件 2");
        
        // 等待处理完成
        System.out.println("\n--- 等待事件处理 ---");
        Thread.sleep(3000);
        
        // 显示性能统计
        StreamEventBusFacade.EventBusStatistics stats = eventBus.getStatistics();
        System.out.println("\n--- 性能统计 ---");
        System.out.println("处理消息数: " + stats.getProcessedMessages());
        System.out.println("失败消息数: " + stats.getFailedMessages());
        System.out.println("超时消息数: " + stats.getTimeoutMessages());
        System.out.println("活跃线程数: " + stats.getActiveThreads());
        System.out.println("队列任务数: " + stats.getQueuedTasks());
        System.out.println("完成任务数: " + stats.getCompletedTasks());
        System.out.println("总处理器数: " + stats.getTotalHandlers());
        
        // 关闭事件总线
        eventBus.shutdown();
        System.out.println("\n=== 示例完成 ===");
    }
} 