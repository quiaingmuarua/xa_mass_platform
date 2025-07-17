package com.xa.mass.base.channel.eventbus.example;

import com.xa.mass.base.channel.eventbus.core.MassEvent;
import com.xa.mass.base.channel.eventbus.core.MassSubscribe;
import com.xa.mass.base.channel.eventbus.core.StreamEventBusFacade;
import com.xa.mass.base.channel.messaging.api.MessageStream;
import com.xa.mass.base.channel.messaging.api.MessageStreamFactory;
import com.xa.mass.base.channel.messaging.redis.RedisConnectionManager;

import java.time.Instant;

public class StreamEventBusExample {
    // 自定义事件类型
    public static class TestEvent implements MassEvent {
        private final String message;
        public TestEvent(String message) { this.message = message; }
        public String getMessage() { return message; }
        @Override public String toString() { return "TestEvent{" + message + '}'; }

        @Override
        public String getEventId() {
            return "test-" + System.currentTimeMillis();
        }

        @Override
        public Instant getTimestamp() {
            return Instant.now();
        }

        @Override
        public String getDescription() {
            return "Test event: " + message;
        }
    }

    // 事件监听器
    public static class TestListener {
        @MassSubscribe
        public void onTestEvent(TestEvent event) {
            System.out.println("[Listener] 收到事件: " + event.getMessage());
        }
    }

    // 第二个事件类型
    public static class AnotherEvent implements MassEvent {
        private final int number;
        public AnotherEvent(int number) { this.number = number; }
        public int getNumber() { return number; }
        @Override public String toString() { return "AnotherEvent{" + number + '}'; }
        @Override public String getEventId() { return "another-" + number; }
        @Override public Instant getTimestamp() { return Instant.now(); }
        @Override public String getDescription() { return "AnotherEvent: " + number; }
    }

    // 第二个监听器
    public static class AnotherListener {
        @MassSubscribe
        public void onAnotherEvent(AnotherEvent event) {
            System.out.println("[AnotherListener] 收到事件: " + event.getNumber());
        }
    }

    public static void main(String[] args) throws Exception {
        // 一行切换内存/redis实现
        String streamType = "redis"; // 改为"redis"即可切换
        
        // 创建Redis连接
        RedisConnectionManager.init("localhost", 6379, null, 0);
        
        // 解决方案：为每种事件类型创建专门的事件总线，避免接口反序列化问题
        MessageStream<TestEvent> testStream = MessageStreamFactory.create(streamType, "test-bus", TestEvent.class);
        MessageStream<AnotherEvent> anotherStream = MessageStreamFactory.create(streamType, "another-bus", AnotherEvent.class);
        
        // 构建事件总线
        StreamEventBusFacade<TestEvent> testEventBus = new StreamEventBusFacade<>(testStream);
        StreamEventBusFacade<AnotherEvent> anotherEventBus = new StreamEventBusFacade<>(anotherStream);
        
        TestListener testListener = new TestListener();
        AnotherListener anotherListener = new AnotherListener();
        
        // 注册监听器到各自的事件总线
        testEventBus.register(testListener);
        anotherEventBus.register(anotherListener);
        
        // 发布两种事件到各自的事件总线
        testEventBus.post(new TestEvent("Hello EventBus!"));
        anotherEventBus.post(new AnotherEvent(42));
        
        // 批量发布事件
        for (int i = 0; i < 5; i++) {
            testEventBus.post(new TestEvent("Batch event " + i));
            anotherEventBus.post(new AnotherEvent(i));
        }
        System.out.println("[Main] 批量事件发布完成");
        Thread.sleep(3000);
        
        // 注销一个监听器
        testEventBus.unregister(testListener);
        System.out.println("[Main] 注销TestListener后再发事件");
        testEventBus.post(new TestEvent("Should not be received by TestListener"));
        anotherEventBus.post(new AnotherEvent(99));
        
        // 等待事件被消费
        Thread.sleep(1500);
        
        // 关闭事件总线
        testEventBus.shutdown();
        anotherEventBus.shutdown();
        
        System.out.println("[Main] 示例完成");
    }
} 