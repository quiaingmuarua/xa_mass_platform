# EventBus 事件总线模块

## 📖 概述

EventBus 模块提供高性能、类型安全的事件总线能力，支持本地内存和分布式Redis实现。通过泛型设计，既支持任意POJO作为事件，又提供结构化事件的完整trace支持。

## 🎯 核心特性

- **🔧 泛型支持**：`StreamEventBusFacade<T>` 支持任意类型事件
- **📊 完整Trace**：MassEvent提供eventId、timestamp、traceId等元数据
- **⚡ 高性能**：精确匹配分发，20K+ events/sec吞吐量
- **🔄 多实现**：内存 (InMemoryMessageStream) / Redis (LettuceRedisStream)
- **🛡️ 线程安全**：ConcurrentHashMap + CopyOnWriteArrayList
- **🎭 灵活注册**：@MassSubscribe注解 + 反射扫描

---

## 🏗️ 目录结构

```
eventbus/
├── core/                              # 核心接口与实现
│   ├── EventBusFacade.java            # 泛型事件总线接口
│   ├── StreamEventBusFacade.java      # 流式事件总线实现
│   ├── MassEventDispatcher.java       # 高性能事件分发器
│   ├── HandlerWrapper.java            # 处理器包装（支持泛型）
│   ├── MassEvent.java                 # 结构化事件接口
│   └── MassSubscribe.java             # 监听器注解
├── example/                           # 示例与最佳实践
└── README.md                          # 本文档
```

---

## 🚀 快速开始

### 场景1：标准化事件（推荐用于生产环境）

```java
// 使用MassEvent获得完整的trace支持
var stream = new InMemoryMessageStream<MassEvent>("events", MassEvent.class);
var eventBus = new StreamEventBusFacade<MassEvent>(stream);

// 监听器
class UserEventListener {
    @MassSubscribe
    public void onUserRegistered(UserRegisteredEvent event) {
        log.info("用户注册: {} [Trace: {}]", 
            event.getDescription(), event.getTraceId());
    }
}

// 注册监听器
eventBus.register(new UserEventListener());

// 发布结构化事件
eventBus.post(new UserRegisteredEvent("user123", "用户注册成功", 
    "trace-001", "request-001"));
```

### 场景2：轻量级POJO事件（快速开发）

```java
// 使用Object类型支持任意POJO
var stream = new InMemoryMessageStream<Object>("simple-events", Object.class);
var eventBus = new StreamEventBusFacade<Object>(stream);

// 简单POJO事件
public record OrderCreated(String orderId, double amount) {}
public record PaymentProcessed(String paymentId, String status) {}

// 监听器
class OrderListener {
    @MassSubscribe
    public void onOrderCreated(OrderCreated event) {
        log.info("订单创建: {} - {}", event.orderId(), event.amount());
    }
    
    @MassSubscribe
    public void onPaymentProcessed(PaymentProcessed event) {
        log.info("支付完成: {} - {}", event.paymentId(), event.status());
    }
}

// 发布轻量级事件
eventBus.post(new OrderCreated("order-123", 99.99));
eventBus.post(new PaymentProcessed("pay-456", "SUCCESS"));
```

### 场景3：混合事件类型（灵活应用）

```java
// 同时支持结构化事件和简单对象
var stream = new InMemoryMessageStream<Object>("mixed-events", Object.class);
var eventBus = new StreamEventBusFacade<Object>(stream);

class MixedListener {
    @MassSubscribe
    public void onStructuredEvent(UserRegisteredEvent event) {
        // 处理有trace的结构化事件
        log.info("结构化事件: {} [{}]", event.getDescription(), event.getTraceId());
    }
    
    @MassSubscribe
    public void onStringMessage(String message) {
        // 处理简单字符串事件
        log.info("字符串事件: {}", message);
    }
    
    @MassSubscribe
    public void onSimpleNotification(SimpleNotification notification) {
        // 处理业务POJO
        log.info("通知事件: {}", notification.message());
    }
}

// 发布不同类型的事件
eventBus.post(new UserRegisteredEvent(...));        // 结构化事件
eventBus.post("系统维护通知");                         // 字符串事件
eventBus.post(new SimpleNotification("更新完成"));    // POJO事件
```

---

## 🌐 分布式支持

### Redis 分布式事件总线

```java
// 配置Redis连接
RedisConnectionManager.configure("localhost", 6379);

// 创建Redis Stream
var redisStream = new LettuceRedisStream<MassEvent>(
    "distributed-events", MassEvent.class);

// 创建分布式事件总线
var eventBus = new StreamEventBusFacade<MassEvent>(redisStream);

// 跨服务事件通信
eventBus.post(new UserRegisteredEvent("user456", "分布式用户注册", 
    generateTraceId(), generateRequestId()));
```

### Redis 序列化注意事项

**问题：** 使用 Redis 时，如果直接使用 `MassEvent` 接口作为泛型类型，会遇到反序列化错误：
```
Interfaces can't be instantiated! Register an InstanceCreator or a TypeAdapter for this type.
```

**解决方案：**

1. **使用具体事件类型**（推荐）：
```java
// 为每种事件类型创建专门的事件总线
MessageStream<UserLoginEvent> loginStream = MessageStreamFactory.create("redis", "login-events", UserLoginEvent.class);
MessageStream<OrderCreatedEvent> orderStream = MessageStreamFactory.create("redis", "order-events", OrderCreatedEvent.class);

StreamEventBusFacade<UserLoginEvent> loginBus = new StreamEventBusFacade<>(loginStream);
StreamEventBusFacade<OrderCreatedEvent> orderBus = new StreamEventBusFacade<>(orderStream);
```

2. **使用多态序列化**（高级用法）：
```java
// 创建支持多态的 Gson
Gson polymorphicGson = new GsonBuilder()
    .registerTypeAdapter(MassEvent.class, new MassEventTypeAdapter())
    .create();

// 创建支持多态的 Redis 流
MessageStream<MassEvent> stream = new LettuceRedisStream<>("polymorphic-bus", MassEvent.class, extraParams, polymorphicGson);
StreamEventBusFacade<MassEvent> eventBus = new StreamEventBusFacade<>(stream);
```

详细的多态序列化实现请参考 `PolymorphicEventBusExample.java`。

---

## 💡 最佳实践

### 1. 类型选择指南

| 场景 | 推荐类型 | 特点 |
|------|----------|------|
| **生产环境** | `StreamEventBusFacade<MassEvent>` | 完整trace、元数据、标准化 |
| **快速开发** | `StreamEventBusFacade<Object>` | 轻量级、无约束、灵活 |
| **混合应用** | `StreamEventBusFacade<Object>` | 同时支持多种事件类型 |

### 2. 事件设计模式

```java
// ✅ 推荐：结构化事件（生产环境）
public class UserRegisteredEvent extends MassEvent.BaseMassEvent {
    private final String userId;
    private final String email;
    
    public UserRegisteredEvent(String userId, String email, String traceId) {
        super("USER_REGISTERED", null, "用户注册", 
              Map.of("userId", userId, "email", email), traceId, null);
        this.userId = userId;
        this.email = email;
    }
}

// ✅ 推荐：简单POJO（快速开发）
public record OrderStatusChanged(String orderId, String fromStatus, String toStatus) {}

// ⚠️ 可选：直接使用基础类型
// eventBus.post("简单字符串通知");
// eventBus.post(Map.of("type", "notification", "message", "系统更新"));
```

### 3. 监听器最佳实践

```java
@Component
public class UserEventListener {
    
    @MassSubscribe
    public void onUserRegistered(UserRegisteredEvent event) {
        // ✅ 使用结构化日志
        log.info("用户注册事件 userId={} traceId={}", 
            event.getUserId(), event.getTraceId());
        
        // ✅ 异常处理
        try {
            userService.sendWelcomeEmail(event.getUserId());
        } catch (Exception e) {
            log.error("发送欢迎邮件失败 userId={}", event.getUserId(), e);
        }
    }
    
    @MassSubscribe
    public void onOrderCreated(OrderCreated order) {
        // ✅ 幂等性处理
        if (orderProcessed.contains(order.orderId())) {
            log.debug("订单已处理，跳过 orderId={}", order.orderId());
            return;
        }
        
        inventoryService.reserveItems(order);
        orderProcessed.add(order.orderId());
    }
}
```

---

## 📊 性能特征

| 指标 | 数值 | 说明 |
|------|------|------|
| **吞吐量** | 20K+ events/sec | 50个监听器，1000个事件 |
| **延迟** | < 2ms | 内存实现，精确匹配 |
| **内存占用** | 低 | ConcurrentHashMap + 预编译反射 |
| **线程安全** | ✅ | CopyOnWriteArrayList保证读性能 |

---

## 🔧 高级配置

### 自定义线程池

```java
public class CustomStreamEventBusFacade<T> extends StreamEventBusFacade<T> {
    public CustomStreamEventBusFacade(MessageStream<T> stream) {
        super(stream);
        // 可以通过继承自定义线程池配置
    }
}
```

### 事件过滤与路由

```java
class ConditionalListener {
    @MassSubscribe
    public void onImportantUserEvent(UserRegisteredEvent event) {
        // 业务逻辑过滤
        if (event.getUserLevel().equals("VIP")) {
            // 处理VIP用户事件
            processVipUser(event);
        }
    }
}
```

---

## ⚠️ 安全和性能注意事项

### 🔒 安全问题

#### 1. 反序列化安全风险 (严重)
**问题：** 多态 TypeAdapter 存在任意类加载风险
```java
// 🚨 危险代码 - 请勿在生产环境使用
Class<?> clazz = Class.forName(className);  // 可加载任意类
```

**解决方案：** 使用类白名单验证
```java
// ✅ 安全实现
private static final Set<String> ALLOWED_CLASSES = Set.of(
    "com.xa.mass.base.channel.eventbus.example.UserLoginEvent",
    "com.xa.mass.base.channel.eventbus.example.OrderCreatedEvent"
);

if (!ALLOWED_CLASSES.contains(className)) {
    throw new JsonParseException("Unauthorized class: " + className);
}
```

#### 2. 反射访问控制
**建议：** 只允许 public 方法作为事件处理器
```java
// ✅ 推荐：使用 public 方法
@MassSubscribe
public void onUserEvent(UserEvent event) { ... }

// ❌ 避免：private 方法会被强制访问
```

### ⚡ 性能问题

#### 1. 固定线程池限制
**问题：** 固定4线程可能成为性能瓶颈
**建议：** 根据CPU核心数和业务负载调整
```java
// 当前实现：固定4线程
// 建议：CPU核心数 * 2 的动态配置
```

#### 2. 批量ACK可靠性
**风险：** 任务提交失败但仍被ACK，可能导致消息丢失
**建议：** 只ACK成功处理的消息

#### 3. 反射性能开销
**影响：** 高频事件场景下反射调用开销明显
**优化：** 考虑使用 MethodHandle 替代反射

#### 4. ~~Redis连接池误解~~ (已纠正)
**重要说明：** Lettuce **不需要**连接池！
- Lettuce 连接本身是线程安全的
- 单个连接可以安全地在多线程间共享
- 连接池会增加不必要的复杂性
- 当前 `RedisConnectionManager` 的单连接设计是正确的

### 📊 性能监控指标

推荐监控以下指标：
- 线程池活跃线程数/队列长度
- 事件处理延迟 (P95, P99)
- 消息ACK失败率
- 内存使用趋势

### 🔧 生产环境建议

1. **禁用多态序列化**：使用具体事件类型避免安全风险
2. **配置合适线程池**：根据业务负载调整线程数
3. **添加监控报警**：关键指标异常及时告警
4. **定期性能测试**：验证系统在预期负载下的表现

详细的安全和性能问题分析请参考：[SECURITY_PERFORMANCE_ISSUES.md](./SECURITY_PERFORMANCE_ISSUES.md)
        if (event.getMetadata().containsKey("vip")) {
            handleVipUser(event);
        }
    }
}
```

---

## 🚀 迁移指南

### 从旧版本迁移

```java
// 旧版本 (已废弃)
GuavaEventBusFacade oldEventBus = new GuavaEventBusFacade(4);

// 新版本 (推荐)
var stream = new InMemoryMessageStream<MassEvent>("events", MassEvent.class);
var newEventBus = new StreamEventBusFacade<MassEvent>(stream);

// API兼容，只需更换实现
newEventBus.register(listener);
newEventBus.post(event);
```

---

## 📚 相关文档

- [GenericEventBusExample.java](../../../test/java/com/xa/mass/base/channel/eventbus/core/GenericEventBusExample.java) - 完整示例
- [StreamEventBusFacadeTest.java](../../../test/java/com/xa/mass/base/channel/eventbus/core/StreamEventBusFacadeTest.java) - 测试用例
- [MessageStream 接口](../../messaging/api/MessageStream.java) - 底层消息流抽象 