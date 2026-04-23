package com.xa.mass.base.channel.eventbus.core;

import com.xa.mass.base.channel.messaging.memory.InMemoryMessageStream;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 泛型EventBus示例：展示如何支持任意对象作为事件
 */
public class GenericEventBusExample {

    // 示例1：使用MassEvent接口的标准事件
    @Test
    public void testStandardMassEventUsage() throws Exception {
        var stream = new InMemoryMessageStream<MassEvent>("standard-events", MassEvent.class);
        var eventBus = new StreamEventBusFacade<MassEvent>(stream);

        CountDownLatch latch = new CountDownLatch(1);

        // 注册监听器
        eventBus.register(new Object() {
            @MassSubscribe
            public void handleUserEvent(UserRegisteredEvent event) {
                System.out.println("收到用户注册事件: " + event.getDescription());
                System.out.println("TraceId: " + event.getTraceId());
                latch.countDown();
            }
        });

        // 发送标准MassEvent
        eventBus.post(new UserRegisteredEvent("user123", "用户注册成功", "trace-001", "req-001"));

        assertTrue(latch.await(1000, TimeUnit.MILLISECONDS));
        eventBus.shutdown();
    }

    // 示例2：使用任意POJO作为事件（通过Object泛型）
    @Test
    public void testArbitraryPojoEventUsage() throws Exception {
        var stream = new InMemoryMessageStream<Object>("pojo-events", Object.class);
        var eventBus = new StreamEventBusFacade<Object>(stream);

        CountDownLatch latch = new CountDownLatch(2);

        // 注册监听器处理不同类型的POJO事件
        eventBus.register(new Object() {
            @MassSubscribe
            public void handleOrderEvent(OrderCreatedEvent event) {
                System.out.println("收到订单事件: " + event.orderId + " - " + event.amount);
                latch.countDown();
            }

            @MassSubscribe
            public void handlePaymentEvent(PaymentProcessedEvent event) {
                System.out.println("收到支付事件: " + event.paymentId + " - " + event.status);
                latch.countDown();
            }
        });

        // 发送任意POJO事件
        eventBus.post(new OrderCreatedEvent("order-123", 99.99));
        eventBus.post(new PaymentProcessedEvent("pay-456", "SUCCESS"));

        assertTrue(latch.await(2000, TimeUnit.MILLISECONDS));
        eventBus.shutdown();
    }

    // 示例3：混合使用，既有结构化事件，又有简单POJO
    @Test
    public void testMixedEventTypes() throws Exception {
        var stream = new InMemoryMessageStream<Object>("mixed-events", Object.class);
        var eventBus = new StreamEventBusFacade<Object>(stream);

        CountDownLatch latch = new CountDownLatch(3);

        eventBus.register(new Object() {
            @MassSubscribe
            public void handleUserEvent(UserRegisteredEvent event) {
                System.out.println("处理用户注册事件: " + event.getDescription() +
                    " [Trace: " + event.getTraceId() + "]");
                latch.countDown();
            }

            @MassSubscribe
            public void handleStringEvent(String message) {
                System.out.println("处理字符串事件: " + message);
                latch.countDown();
            }

            @MassSubscribe
            public void handlePojoEvent(SimpleNotification notification) {
                System.out.println("处理通知事件: " + notification.message);
                latch.countDown();
            }
        });

        // 发送不同类型的事件
        eventBus.post(new UserRegisteredEvent("user456", "新用户注册", "trace-002", "req-002"));
        eventBus.post("这是一个简单的字符串事件");
        eventBus.post(new SimpleNotification("系统维护通知"));

        assertTrue(latch.await(2000, TimeUnit.MILLISECONDS));
        eventBus.shutdown();
    }

    // =============================================================================
    // 事件类定义
    // =============================================================================

    // 标准MassEvent实现
    public static class UserRegisteredEvent extends MassEvent.BaseMassEvent {
        private final String userId;

        public UserRegisteredEvent(String userId, String description, String traceId, String requestId) {
            super("USER_REGISTERED", null, description, null, traceId, requestId);
            this.userId = userId;
        }

        public String getUserId() { return userId; }
    }

    // 简单POJO事件
    public static class OrderCreatedEvent {
        public final String orderId;
        public final double amount;

        public OrderCreatedEvent(String orderId, double amount) {
            this.orderId = orderId;
            this.amount = amount;
        }
    }

    public static class PaymentProcessedEvent {
        public final String paymentId;
        public final String status;

        public PaymentProcessedEvent(String paymentId, String status) {
            this.paymentId = paymentId;
            this.status = status;
        }
    }

    public static class SimpleNotification {
        public final String message;

        public SimpleNotification(String message) {
            this.message = message;
        }
    }
}
