package com.xa.mass.base.channel.eventbus.core;

import com.xa.mass.base.channel.messaging.api.MessageStream;
import com.xa.mass.base.channel.messaging.memory.InMemoryMessageStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class StreamEventBusFacadeTest {
    private StreamEventBusFacade<MassEvent> eventBus;
    private MessageStream<MassEvent> stream;

    // 测试事件类型
    public static class TestEvent implements MassEvent {
        private final String message;
        public TestEvent(String message) { this.message = message; }
        public String getMessage() { return message; }
        @Override public String toString() { return "TestEvent{" + message + '}'; }
        @Override public String getEventId() { return "test-" + System.nanoTime(); }
        @Override public Instant getTimestamp() { return Instant.now(); }
        @Override public String getDescription() { return "TestEvent: " + message; }
    }

    public static class NumberEvent implements MassEvent {
        private final int number;
        public NumberEvent(int number) { this.number = number; }
        public int getNumber() { return number; }
        @Override public String toString() { return "NumberEvent{" + number + '}'; }
        @Override public String getEventId() { return "number-" + number; }
        @Override public Instant getTimestamp() { return Instant.now(); }
        @Override public String getDescription() { return "NumberEvent: " + number; }
    }

    // 测试监听器
    public static class TestListener {
        private final CountDownLatch latch;
        private final AtomicInteger count = new AtomicInteger(0);
        private volatile String lastMessage;

        public TestListener(CountDownLatch latch) { this.latch = latch; }

        @MassSubscribe
        public void onTestEvent(TestEvent event) {
            lastMessage = event.getMessage();
            count.incrementAndGet();
            latch.countDown();
        }

        public String getLastMessage() { return lastMessage; }
        public int getCount() { return count.get(); }
    }

    public static class NumberListener {
        private final CountDownLatch latch;
        private final AtomicInteger sum = new AtomicInteger(0);
        private final AtomicInteger count = new AtomicInteger(0);

        public NumberListener(CountDownLatch latch) { this.latch = latch; }

        @MassSubscribe
        public void onNumberEvent(NumberEvent event) {
            sum.addAndGet(event.getNumber());
            count.incrementAndGet();
            latch.countDown();
        }

        public int getSum() { return sum.get(); }
        public int getCount() { return count.get(); }
    }

    @BeforeEach
    public void setUp() {
        stream = new InMemoryMessageStream<>("test-eventbus", MassEvent.class);
        eventBus = new StreamEventBusFacade<>(stream);
    }

    @AfterEach
    public void tearDown() {
        if (eventBus != null) {
            eventBus.shutdown();
        }
    }

    @Test
    public void testRegisterAndPost() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        TestListener listener = new TestListener(latch);

        eventBus.register(listener);
        eventBus.post(new TestEvent("Hello World"));

        assertTrue(latch.await(1500, TimeUnit.MILLISECONDS), "Event should be received within 1500ms");
        assertEquals("Hello World", listener.getLastMessage());
        assertEquals(1, listener.getCount());
    }

    @Test
    public void testMultipleEventTypes() throws Exception {
        CountDownLatch latch = new CountDownLatch(3); // 2个TestEvent + 1个NumberEvent
        TestListener testListener = new TestListener(latch);
        NumberListener numberListener = new NumberListener(latch);

        eventBus.register(testListener);
        eventBus.register(numberListener);

        eventBus.post(new TestEvent("Message1"));
        eventBus.post(new NumberEvent(42));
        eventBus.post(new TestEvent("Message2"));

        assertTrue(latch.await(2000, TimeUnit.MILLISECONDS), "All events should be received");
        assertEquals(2, testListener.getCount());
        // 异步处理不保证顺序，只验证收到了正确数量的事件
        assertTrue("Message1".equals(testListener.getLastMessage()) || "Message2".equals(testListener.getLastMessage()),
            "Should receive Message1 or Message2");
        assertEquals(1, numberListener.getCount());
        assertEquals(42, numberListener.getSum());
    }

    @Test
    public void testUnregisterListener() throws Exception {
        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);
        TestListener listener = new TestListener(latch1);

        // 注册并发送第一个事件
        eventBus.register(listener);
        eventBus.post(new TestEvent("Before unregister"));
        assertTrue(latch1.await(1500, TimeUnit.MILLISECONDS), "First event should be received");

        // 注销监听器
        eventBus.unregister(listener);
        
        // 发送第二个事件，不应该被接收
        eventBus.post(new TestEvent("After unregister"));
        Thread.sleep(200); // 等待一下确保事件不会被处理

        assertEquals(1, listener.getCount()); // 还是只有1个事件
        assertEquals("Before unregister", listener.getLastMessage());
    }

    @Test
    public void testBatchEvents() throws Exception {
        int eventCount = 10;
        CountDownLatch latch = new CountDownLatch(eventCount);
        NumberListener listener = new NumberListener(latch);

        eventBus.register(listener);

        // 批量发送事件
        for (int i = 1; i <= eventCount; i++) {
            eventBus.post(new NumberEvent(i));
        }

        assertTrue(latch.await(1000, TimeUnit.MILLISECONDS), "All batch events should be received");
        assertEquals(eventCount, listener.getCount());
        assertEquals(55, listener.getSum()); // 1+2+...+10 = 55
    }

    @Test
    public void testGetListenerCount() {
        assertEquals(0, eventBus.getListenerCount());

        TestListener listener1 = new TestListener(new CountDownLatch(1));
        NumberListener listener2 = new NumberListener(new CountDownLatch(1));

        eventBus.register(listener1);
        assertEquals(1, eventBus.getListenerCount());

        eventBus.register(listener2);
        assertEquals(2, eventBus.getListenerCount());

        eventBus.unregister(listener1);
        assertEquals(1, eventBus.getListenerCount());
    }

    @Test
    public void testGetHandlerCount() {
        TestListener listener = new TestListener(new CountDownLatch(1));
        eventBus.register(listener);

        assertEquals(1, eventBus.getHandlerCount(TestEvent.class));
        assertEquals(0, eventBus.getHandlerCount(NumberEvent.class));
    }

    @Test
    public void testGetStreamInfo() {
        String info = eventBus.getStreamInfo();
        assertTrue(info.contains("test-eventbus"), "Stream info should contain stream name");
        assertTrue(info.contains("Handlers: 0"), "Stream info should contain handler count");
    }

    @Test
    public void testPostNullEvent() {
        assertThrows(IllegalArgumentException.class, () -> {
            eventBus.post(null);
        });
    }

    @Test
    public void testRegisterFunctionHandler() {
        assertThrows(UnsupportedOperationException.class, () -> {
            eventBus.register(TestEvent.class, event -> {});
        });
    }

    @Test
    public void testUnregisterFunctionHandler() {
        assertThrows(UnsupportedOperationException.class, () -> {
            eventBus.unregister(TestEvent.class, event -> {});
        });
    }

    @Test
    public void testDispatchPerformance() throws Exception {
        // 创建多个监听器来模拟复杂场景
        TestListener[] listeners = new TestListener[50];
        for (int i = 0; i < listeners.length; i++) {
            listeners[i] = new TestListener(new CountDownLatch(1));
            eventBus.register(listeners[i]);
        }
        
        // 预热
        for (int i = 0; i < 100; i++) {
            eventBus.post(new TestEvent("warmup"));
        }
        
        // 性能测试
        long startTime = System.nanoTime();
        int iterations = 1000;
        for (int i = 0; i < iterations; i++) {
            eventBus.post(new TestEvent("performance test " + i));
        }
        long endTime = System.nanoTime();
        
        long durationMs = (endTime - startTime) / 1_000_000;
        System.out.println("=== Dispatch Performance Test ===");
        System.out.println("Listeners: " + listeners.length);
        System.out.println("Events: " + iterations);
        System.out.println("Duration: " + durationMs + " ms");
        System.out.println("Events/sec: " + (iterations * 1000 / Math.max(durationMs, 1)));
        
        // 验证所有事件都被处理了
        Thread.sleep(100); // 等待异步处理完成
        for (TestListener listener : listeners) {
            assertTrue(listener.getCount() >= iterations, 
                "Each listener should receive at least " + iterations + " events, got " + listener.getCount());
        }
    }
} 