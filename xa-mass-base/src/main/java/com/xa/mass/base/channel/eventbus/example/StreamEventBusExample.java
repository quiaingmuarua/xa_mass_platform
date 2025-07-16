package com.xa.mass.base.channel.eventbus.example;

import com.xa.mass.base.channel.messaging.api.MessageStream;
import com.xa.mass.base.channel.messaging.api.MessageStreamFactory;
import com.xa.mass.base.channel.eventbus.core.MassEvent;
import com.xa.mass.base.channel.eventbus.core.MassSubscribe;
import com.xa.mass.base.channel.eventbus.core.StreamEventBusFacade;

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
            return "";
        }

        @Override
        public Instant getTimestamp() {
            return null;
        }

        @Override
        public String getDescription() {
            return "";
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
        String streamType = "memory"; // 改为"redis"即可切换
        MessageStream<MassEvent> stream = MessageStreamFactory.create(streamType, "test-bus", MassEvent.class);
        // 构建事件总线
        StreamEventBusFacade eventBus = new StreamEventBusFacade(stream);
        TestListener testListener = new TestListener();
        AnotherListener anotherListener = new AnotherListener();
        // 注册两个监听器
        eventBus.register(testListener);
        eventBus.register(anotherListener);
        // 发布两种事件
        eventBus.post(new TestEvent("Hello EventBus!"));
        eventBus.post(new AnotherEvent(42));
        // 批量发布事件
        for (int i = 0; i < 5; i++) {
            eventBus.post(new TestEvent("Batch event " + i));
            eventBus.post(new AnotherEvent(i));
        }
        // 注销一个监听器
        eventBus.unregister(testListener);
        System.out.println("[Main] 注销TestListener后再发事件");
        eventBus.post(new TestEvent("Should not be received by TestListener"));
        eventBus.post(new AnotherEvent(99));
        // 等待事件被消费
        Thread.sleep(1500);
        // 关闭事件总线
        eventBus.shutdown();
    }
} 