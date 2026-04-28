package com.xa.mass.base.channel.eventbus.core;

import com.xa.mass.base.channel.messaging.api.MessageStream;
import com.xa.mass.base.channel.messaging.memory.InMemoryMessageStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OptimizedStreamEventBusFacade 单元测试
 */
class OptimizedStreamEventBusFacadeTest {

    private StreamEventBusFacade<Object> eventBus;
    private MessageStream<Object> stream;
    private EventBusConfig config;

    // 测试事件类型
    static class TestEvent implements MassEvent {
        private final String id;
        private final String message;

        public TestEvent(String id, String message) {
            this.id = id;
            this.message = message;
        }

        public String getId() { return id; }
        public String getMessage() { return message; }

        @Override
        public String getEventId() { return id; }

        @Override
        public Instant getTimestamp() { return Instant.now(); }

        @Override
        public String getDescription() { return message; }

        @Override
        public String toString() { return "TestEvent{id='" + id + "', message='" + message + "'}"; }
    }

    // 测试监听器
    static class TestListener {
        private final AtomicInteger processedCount = new AtomicInteger(0);
        private final CountDownLatch latch;

        public TestListener(int expectedEvents) {
            this.latch = new CountDownLatch(expectedEvents);
        }

        @MassSubscribe
        public void onTestEvent(TestEvent event) {
            processedCount.incrementAndGet();
            latch.countDown();
        }

        @MassSubscribe
        public void onStringEvent(String message) {
            processedCount.incrementAndGet();
            latch.countDown();
        }

        public int getProcessedCount() { return processedCount.get(); }

        public boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }
    }

    // 慢处理监听器
    static class SlowListener {
        private final AtomicInteger processedCount = new AtomicInteger(0);
        private final CountDownLatch latch;
        private final long delayMs;

        public SlowListener(int expectedEvents, long delayMs) {
            this.latch = new CountDownLatch(expectedEvents);
            this.delayMs = delayMs;
        }

        @MassSubscribe
        public void onTestEvent(TestEvent event) {
            try {
                Thread.sleep(delayMs);
                processedCount.incrementAndGet();
                latch.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        public int getProcessedCount() { return processedCount.get(); }

        public boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }
    }

    // 错误监听器
    static class ErrorListener {
        private final AtomicInteger errorCount = new AtomicInteger(0);

        @MassSubscribe
        public void onTestEvent(TestEvent event) {
            errorCount.incrementAndGet();
            throw new RuntimeException("Simulated processing error");
        }

        public int getErrorCount() { return errorCount.get(); }
    }

    @BeforeEach
    void setUp() {
        stream = new InMemoryMessageStream<>("test-stream", Object.class);
        config = EventBusConfig.defaultConfig()
            .setBatchSize(5)
            .setBatchTimeoutMs(100L)
            .setHandlerTimeoutSeconds(5L)
            .setCorePoolSize(2)
            .setMaxPoolSize(4);
        eventBus = new StreamEventBusFacade<>(stream, config);
    }

    @AfterEach
    void tearDown() {
        if (eventBus != null) {
            eventBus.shutdown();
        }
    }

    @Test
    @DisplayName("基本事件发布和处理")
    void testBasicEventProcessing() throws InterruptedException {
        // Arrange
        TestListener listener = new TestListener(3);
        eventBus.register(listener);

        // Act
        eventBus.post(new TestEvent("1", "First event"));
        eventBus.post(new TestEvent("2", "Second event"));
        eventBus.post("String event");

        // Assert
        assertTrue(listener.awaitCompletion(2, TimeUnit.SECONDS), "Events should be processed within timeout");
        assertEquals(3, listener.getProcessedCount(), "All events should be processed");
    }

    @Test
    @DisplayName("批量事件处理")
    void testBatchProcessing() throws InterruptedException {
        // Arrange
        TestListener listener = new TestListener(10);
        eventBus.register(listener);

        // Act - 发布10个事件，触发批量处理
        for (int i = 1; i <= 10; i++) {
            eventBus.post(new TestEvent(String.valueOf(i), "Event " + i));
        }

        // Assert
        assertTrue(listener.awaitCompletion(3, TimeUnit.SECONDS), "Batch events should be processed");
        assertEquals(10, listener.getProcessedCount(), "All batch events should be processed");
    }

    @Test
    @DisplayName("监听器注册和注销")
    void testListenerRegistrationAndUnregistration() throws InterruptedException {
        // Arrange
        TestListener listener = new TestListener(1);

        // Act - 注册监听器
        eventBus.register(listener);
        assertEquals(2, eventBus.getListenerCount(), "Should have 2 handlers (TestEvent + String)");

        eventBus.post(new TestEvent("1", "Test"));
        assertTrue(listener.awaitCompletion(1, TimeUnit.SECONDS), "Event should be processed");

        // Act - 注销监听器
        eventBus.unregister(listener);
        assertEquals(0, eventBus.getListenerCount(), "Should have no handlers after unregistration");
    }

    @Test
    @DisplayName("异常处理")
    void testErrorHandling() throws InterruptedException {
        // Arrange
        ErrorListener errorListener = new ErrorListener();
        TestListener normalListener = new TestListener(2);

        eventBus.register(errorListener);
        eventBus.register(normalListener);

        // Act
        eventBus.post(new TestEvent("1", "Error event"));
        eventBus.post(new TestEvent("2", "Normal event"));

        // Assert
        assertTrue(normalListener.awaitCompletion(2, TimeUnit.SECONDS), "Normal events should be processed");
        assertEquals(2, normalListener.getProcessedCount(), "Normal listener should process events");
        assertEquals(2, errorListener.getErrorCount(), "Error listener should process and fail");

        // 检查错误统计 - 等待异步处理完成
        Thread.sleep(1000); // 等待统计更新
        StreamEventBusFacade.EventBusStatistics stats = eventBus.getStatistics();
        assertTrue(stats.getFailedMessages() > 0, "Should have failed messages recorded");
    }

    @Test
    @DisplayName("超时处理")
    void testTimeoutHandling() throws InterruptedException {
        // Arrange - 创建短超时配置
        EventBusConfig shortTimeoutConfig = EventBusConfig.defaultConfig()
            .setBatchSize(2)
            .setBatchTimeoutMs(100L)
            .setHandlerTimeoutSeconds(1L); // 1秒超时

        eventBus.shutdown();
        eventBus = new StreamEventBusFacade<>(stream, shortTimeoutConfig);

        SlowListener slowListener = new SlowListener(2, 2000L); // 2秒处理时间
        eventBus.register(slowListener);

        // Act
        eventBus.post(new TestEvent("1", "Slow event 1"));
        eventBus.post(new TestEvent("2", "Slow event 2"));

        // Assert
        Thread.sleep(3000); // 等待超时处理
        StreamEventBusFacade.EventBusStatistics stats = eventBus.getStatistics();
        assertTrue(stats.getTimeoutMessages() > 0, "Should have timeout messages recorded");
    }

//    @Test
//    @DisplayName("性能统计")
//    void testStatistics() throws InterruptedException {
//        // Arrange
//        TestListener listener = new TestListener(5);
//        eventBus.register(listener);
//
//        // Act
//        for (int i = 1; i <= 5; i++) {
//            eventBus.post(new TestEvent(String.valueOf(i), "Event " + i));
//        }
//
//        assertTrue(listener.awaitCompletion(2, TimeUnit.SECONDS), "Events should be processed");
//
//        // Assert
//        StreamEventBusFacade.EventBusStatistics stats = eventBus.getStatistics();
//        assertEquals(5, stats.getProcessedMessages(), "Should process 5 messages");
//        assertEquals(0, stats.getFailedMessages(), "Should have no failed messages");
//        assertEquals(0, stats.getTimeoutMessages(), "Should have no timeout messages");
//        assertEquals(2, stats.getTotalHandlers(), "Should have 2 handlers");
//        assertTrue(stats.getCompletedTasks() >= 5, "Should have completed tasks");
//    }

    @Test
    @DisplayName("配置信息")
    void testConfiguration() {
        // Assert
        assertEquals(config, eventBus.getConfig(), "Should return correct config");
        assertNotNull(eventBus.getStreamInfo(), "Stream info should not be null");
        assertTrue(eventBus.getStreamInfo().contains("test-stream"), "Stream info should contain stream name");
    }

    @Test
    @DisplayName("空事件处理")
    void testNullEventHandling() {
        // Act & Assert
        assertThrows(IllegalArgumentException.class, () -> {
            eventBus.post(null);
        }, "Should throw exception for null event");
    }

    @Test
    @DisplayName("默认配置构造")
    void testDefaultConfigConstruction() {
        // Arrange
        MessageStream<Object> newStream = new InMemoryMessageStream<>("test-stream-2", Object.class);

        // Act
        StreamEventBusFacade<Object> defaultEventBus = new StreamEventBusFacade<>(newStream);

        // Assert
        assertNotNull(defaultEventBus.getConfig(), "Should have default config");
        assertEquals(EventBusConfig.defaultConfig().getCorePoolSize(),
                    defaultEventBus.getConfig().getCorePoolSize(), "Should use default core pool size");

        // Cleanup
        defaultEventBus.shutdown();
    }

    @Test
    @DisplayName("并发处理")
    void testConcurrentProcessing() throws InterruptedException {
        // Arrange
        TestListener listener = new TestListener(20);
        eventBus.register(listener);

        // Act - 并发发布事件
        for (int i = 1; i <= 20; i++) {
            final int eventId = i;
            new Thread(() -> {
                eventBus.post(new TestEvent(String.valueOf(eventId), "Concurrent event " + eventId));
            }).start();
        }

        // Assert
        assertTrue(listener.awaitCompletion(5, TimeUnit.SECONDS), "Concurrent events should be processed");
        assertEquals(20, listener.getProcessedCount(), "All concurrent events should be processed");
    }

    @Test
    @DisplayName("优雅关闭")
    void testGracefulShutdown() throws InterruptedException {
        // Arrange
        TestListener listener = new TestListener(3);
        eventBus.register(listener);

        // Act
        eventBus.post(new TestEvent("1", "Event before shutdown"));
        eventBus.post(new TestEvent("2", "Event before shutdown"));
        eventBus.post(new TestEvent("3", "Event before shutdown"));

        // 立即关闭
        eventBus.shutdown();

        // Assert - 验证统计信息被记录
        StreamEventBusFacade.EventBusStatistics finalStats = eventBus.getStatistics();
        assertNotNull(finalStats, "Final statistics should be available");
    }

    @Test
    @DisplayName("不支持的操作")
    void testUnsupportedOperations() {
        // Assert
        assertThrows(UnsupportedOperationException.class, () -> {
            eventBus.register(TestEvent.class, event -> {});
        }, "Should throw exception for unsupported register method");

        assertThrows(UnsupportedOperationException.class, () -> {
            eventBus.unregister(TestEvent.class, event -> {});
        }, "Should throw exception for unsupported unregister method");
    }
}
