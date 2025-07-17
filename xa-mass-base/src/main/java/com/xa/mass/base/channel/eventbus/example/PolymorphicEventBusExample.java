package com.xa.mass.base.channel.eventbus.example;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.xa.mass.base.channel.eventbus.core.MassEvent;
import com.xa.mass.base.channel.eventbus.core.MassSubscribe;
import com.xa.mass.base.channel.eventbus.core.StreamEventBusFacade;
import com.xa.mass.base.channel.messaging.api.MessageStream;
import com.xa.mass.base.channel.messaging.redis.LettuceRedisStream;
import com.xa.mass.base.channel.messaging.redis.RedisConnectionManager;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 演示如何使用多态序列化支持多种事件类型在同一个事件总线中
 */
public class PolymorphicEventBusExample {

    // 自定义事件类型
    public static class UserLoginEvent implements MassEvent {
        private final String userId;
        private final String loginTime;
        
        public UserLoginEvent(String userId, String loginTime) {
            this.userId = userId;
            this.loginTime = loginTime;
        }
        
        public String getUserId() { return userId; }
        public String getLoginTime() { return loginTime; }
        
        @Override
        public String getEventId() { return "login-" + userId + "-" + System.currentTimeMillis(); }
        
        @Override
        public Instant getTimestamp() { return Instant.now(); }
        
        @Override
        public String getDescription() { return "User " + userId + " logged in at " + loginTime; }
        
        @Override
        public String toString() { return "UserLoginEvent{userId='" + userId + "', loginTime='" + loginTime + "'}"; }
    }

    public static class OrderCreatedEvent implements MassEvent {
        private final String orderId;
        private final double amount;
        
        public OrderCreatedEvent(String orderId, double amount) {
            this.orderId = orderId;
            this.amount = amount;
        }
        
        public String getOrderId() { return orderId; }
        public double getAmount() { return amount; }
        
        @Override
        public String getEventId() { return "order-" + orderId; }
        
        @Override
        public Instant getTimestamp() { return Instant.now(); }
        
        @Override
        public String getDescription() { return "Order " + orderId + " created with amount " + amount; }
        
        @Override
        public String toString() { return "OrderCreatedEvent{orderId='" + orderId + "', amount=" + amount + "}"; }
    }

    // 多态类型适配器
    public static class MassEventTypeAdapter implements JsonSerializer<MassEvent>, JsonDeserializer<MassEvent> {
        private static final String CLASS_META_KEY = "eventClass";

        @Override
        public JsonElement serialize(MassEvent src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject jsonObject = new JsonObject();
            jsonObject.addProperty(CLASS_META_KEY, src.getClass().getName());
            JsonElement jsonTree = context.serialize(src, src.getClass());
            if (jsonTree.isJsonObject()) {
                JsonObject srcObject = jsonTree.getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : srcObject.entrySet()) {
                    jsonObject.add(entry.getKey(), entry.getValue());
                }
            }
            return jsonObject;
        }

        @Override
        public MassEvent deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            if (!json.isJsonObject()) {
                throw new JsonParseException("Expected JsonObject but got " + json.getClass().getSimpleName());
            }
            
            JsonObject jsonObject = json.getAsJsonObject();
            JsonElement classElement = jsonObject.get(CLASS_META_KEY);
            
            if (classElement == null) {
                throw new JsonParseException("Missing class meta information. Expected field: " + CLASS_META_KEY);
            }
            
            String className = classElement.getAsString();
            
            // 移除类型信息，避免反序列化时出现多余字段
            jsonObject.remove(CLASS_META_KEY);
            
            try {
                Class<?> clazz = Class.forName(className);
                return context.deserialize(jsonObject, clazz);
            } catch (ClassNotFoundException e) {
                throw new JsonParseException("Unknown event class: " + className, e);
            }
        }
    }

    // 事件监听器
    public static class EventListener {
        @MassSubscribe
        public void onUserLogin(UserLoginEvent event) {
            System.out.println("[EventListener] 用户登录事件: " + event);
        }
        
        @MassSubscribe
        public void onOrderCreated(OrderCreatedEvent event) {
            System.out.println("[EventListener] 订单创建事件: " + event);
        }
        
        @MassSubscribe
        public void onAnyMassEvent(MassEvent event) {
            System.out.println("[EventListener] 通用事件处理: " + event.getDescription());
        }
    }

    public static void main(String[] args) throws Exception {
        // 初始化Redis连接
        RedisConnectionManager.init("localhost", 6379, null, 0);
        
        // 创建支持多态序列化的Gson
        Gson polymorphicGson = new GsonBuilder()
            .registerTypeAdapter(MassEvent.class, new MassEventTypeAdapter())
            .create();
        
        // 创建Redis流，使用自定义Gson
        Map<String, String> extraParams = new HashMap<>();
        extraParams.put("group", "polymorphic-group");
        extraParams.put("consumerName", "polymorphic-consumer");
        
        MessageStream<MassEvent> stream = new LettuceRedisStream<>("polymorphic-bus", MassEvent.class, extraParams, polymorphicGson);
        
        // 构建事件总线
        StreamEventBusFacade<MassEvent> eventBus = new StreamEventBusFacade<>(stream);
        
        // 注册监听器
        EventListener listener = new EventListener();
        eventBus.register(listener);
        
        System.out.println("=== 多态事件总线示例 ===");
        
        // 发布不同类型的事件
        eventBus.post(new UserLoginEvent("user001", "2025-01-20 13:30:00"));
        eventBus.post(new OrderCreatedEvent("order001", 99.99));
        eventBus.post(new UserLoginEvent("user002", "2025-01-20 13:31:00"));
        eventBus.post(new OrderCreatedEvent("order002", 199.99));
        
        // 等待事件被消费
        Thread.sleep(3000);
        
        // 注销监听器
        eventBus.unregister(listener);
        System.out.println("[Main] 注销监听器后再发事件");
        
        eventBus.post(new UserLoginEvent("user003", "2025-01-20 13:32:00"));
        
        Thread.sleep(1000);
        
        // 关闭事件总线
        eventBus.shutdown();
        
        System.out.println("[Main] 多态事件总线示例完成");
    }
} 