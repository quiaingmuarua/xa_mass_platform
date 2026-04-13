# EventBus 使用示例

## 📖 概述

本目录包含EventBus模块的完整使用示例，展示了不同场景下的最佳实践和性能优化技巧�?

## 📁 示例文件说明

| 文件 | 描述 | 适用场景 |
|------|------|----------|
| `GuavaEventBusExample.java` | Guava本地事件总线示例 | 单应用内部通信 |
| `RedisStreamEventBusExample.java` | Redis分布式事件总线示例 | 跨进�?跨服务通信 |
| `DeviceEventListenerService.java` | 长期运行的事件监听服�?| 后台服务、微服务 |

## 🚀 示例详解

### 1. GuavaEventBusExample - 本地高性能事件总线

#### 适用场景
- **单体应用**: 模块间解耦通信
- **高频事件**: 需要微秒级延迟的场�?
- **内存敏感**: 对内存使用有严格要求的应�?

#### 核心特性展�?
```java
public class GuavaEventBusExample {
    // 多事件类型监听器
    static class MultiEventListener {
        @Subscribe  // 使用Guava的@Subscribe注解
        public void onDeviceOffline(DeviceOfflineEvent event) {
            // 异步处理，不阻塞发布�?
            System.out.println("设备下线: " + event.getDeviceId());
        }
        
        @Subscribe
        public void onTaskAssigned(TaskAssignedEvent event) {
            // 支持不同事件类型
            System.out.println("任务分配: " + event.getTask().getTid());
        }
    }
}
```

#### 运行示例
```bash
cd xa-mass-core
java -cp "target/classes" example.com.xa.mass.base.old.eventbus.GuavaEventBusExample
```

#### 预期输出
```
[Main] 发布事件，线�? main
[Listener] 收到设备下线事件: DeviceOfflineEvent{...}, 线程: pool-1-thread-1
[Listener] 收到设备上线事件: DeviceOnlineEvent{...}, 线程: pool-1-thread-2
[Listener] 收到任务分配事件: TaskAssignedEvent{...}, 线程: pool-1-thread-3
[Main] 所有事件处理完�?
```

#### 性能特点
- **延迟**: 微秒级事件分�?
- **吞吐**: 单线程可处理10�?事件/�?
- **内存**: 零额外对象分配（优化后）

### 2. RedisStreamEventBusExample - 分布式事件总线

#### 适用场景
- **微服务架�?*: 跨服务事件通信
- **水平扩展**: 多实例之间的协调
- **持久化需�?*: 事件需要持久化存储

#### 核心特性展�?
```java
public class RedisStreamEventBusExample {
    public static void main(String[] args) throws InterruptedException {
        // 1. 初始化Redis连接
        RedisConnectionManager.init("localhost", 6379, null, 0);
        
        // 2. 创建分布式事件总线
        EventBusFacade eventBus = EventBusFactory.get("redis");
        
        // 3. 启动后台监听服务（可在不同进程中�?
        Thread listenerThread = new Thread(
            new DeviceEventListenerService(eventBus), 
            "DeviceEventListenerService"
        );
        listenerThread.start();
        
        // 4. 主线程发布事件（可在另一个进程中�?
        for (int i = 1; i <= 5; i++) {
            eventBus.post(new DeviceOfflineEvent("device-" + i, "网络异常", "trace-" + i));
            eventBus.post(new DeviceOnlineEvent("device-" + i, "恢复上线", "trace-" + i));
        }
    }
}
```

#### Redis Stream 架构
```
Redis Stream: mass_event_stream
├── Consumer Group: mass_group
�?  ├── Consumer: consumer-1 (实例1)
�?  ├── Consumer: consumer-2 (实例2)
�?  └── Consumer: consumer-N (实例N)
└── Event Messages
    ├── {event: JSON, type: ClassName}
    └── Auto-ACK after processing
```

#### 运行要求
```bash
# 1. 启动Redis服务
redis-server

# 2. 运行示例
cd xa-mass-core
java -cp "target/classes:$REDIS_LIBS" \
  example.com.xa.mass.base.old.eventbus.RedisStreamEventBusExample
```

#### 容错特�?
- **自动重连**: Redis连接断开自动重试
- **消息确认**: 处理完成后自动ACK
- **故障恢复**: 支持从断点续�?

### 3. DeviceEventListenerService - 长期运行监听服务

#### 适用场景
- **后台守护进程**: 长期运行的事件处理服�?
- **微服务组�?*: 作为独立服务部署
- **监控告警**: 实时监控系统事件

#### 服务设计模式
```java
public class DeviceEventListenerService implements Runnable {
    private final EventBusFacade eventBus;
    
    // 构造函数注入EventBus实例
    public DeviceEventListenerService(EventBusFacade eventBus) {
        this.eventBus = eventBus;
    }
    
    // 使用@MassSubscribe注解（适用于Redis实现�?
    @MassSubscribe
    public void onDeviceOffline(DeviceOfflineEvent event) {
        // 业务处理逻辑
        handleDeviceOffline(event);
    }
    
    @Override
    public void run() {
        // 注册监听�?
        eventBus.register(this);
        
        try {
            // 保持服务运行
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            // 优雅退�?
        } finally {
            eventBus.unregister(this);
        }
    }
}
```

#### 部署方式
```bash
# 作为独立服务运行
java -jar device-event-service.jar

# 或集成到Spring Boot应用
@Component
public class DeviceEventServiceStarter {
    @EventListener(ApplicationReadyEvent.class)
    public void startEventService() {
        // 启动事件监听服务
    }
}
```

## 🎯 最佳实�?

### 1. 监听器设计原�?

#### �?推荐做法
```java
@MassSubscribe
public void onDeviceOffline(DeviceOfflineEvent event) {
    // 1. 快速处�?- 避免长时间阻�?
    if (isImportantDevice(event.getDeviceId())) {
        // 2. 异步处理复杂逻辑
        CompletableFuture.runAsync(() -> {
            sendAlert(event);
            updateDatabase(event);
        });
    }
    
    // 3. 简单状态更新可同步执行
    updateDeviceStatus(event.getDeviceId(), "OFFLINE");
}
```

#### �?避免的做�?
```java
@MassSubscribe
public void onDeviceOffline(DeviceOfflineEvent event) {
    // �?长时间阻塞操�?
    try {
        Thread.sleep(5000);  // 阻塞其他事件处理
    } catch (InterruptedException e) {}
    
    // �?同步网络请求
    httpClient.post("http://slow-service.com/notify", event);
    
    // �?抛出未处理异�?
    throw new RuntimeException("Something wrong");  // 影响其他监听�?
}
```

### 2. 事件设计原则

#### 事件命名规范
```java
// �?推荐：语义清晰的事件�?
DeviceOfflineEvent      // 设备下线
TaskAssignedEvent       // 任务分配
UserLoginSuccessEvent   // 用户登录成功

// �?避免：模糊的事件�?
DeviceEvent            // 太宽�?
StatusChangeEvent      // 不明�?
```

#### 事件数据设计
```java
// �?推荐：包含完整上下文信息
public class TaskAssignedEvent extends MassEvent.BaseMassEvent {
    private final Task task;           // 主要业务对象
    private final String assigneeId;   // 关键业务字段
    private final String assignReason; // 业务原因
    
    // 继承自BaseMassEvent的标准字段：
    // - eventId: 事件唯一标识
    // - timestamp: 事件时间�?
    // - traceId: 链路追踪ID
    // - requestId: 请求标识
}
```

### 3. 异常处理策略

#### 监听器内异常处理
```java
@MassSubscribe
public void onTaskCreated(TaskCreatedEvent event) {
    try {
        // 主要业务逻辑
        processTask(event.getTask());
    } catch (BusinessException e) {
        // 业务异常 - 记录日志，可能需要重�?
        log.warn("处理任务失败，稍后重�? {}", event.getTask().getTid(), e);
        scheduleRetry(event);
    } catch (Exception e) {
        // 系统异常 - 记录错误，跳过处�?
        log.error("处理任务时发生系统错�? {}", event.getTask().getTid(), e);
        reportSystemError(e, event);
    }
}
```

#### 全局异常监控
```java
// 自定义事件分发器包装�?
public class MonitoredEventDispatcher extends MassEventDispatcher {
    private final MetricRegistry metrics;
    
    @Override
    public void dispatch(Object event) {
        Timer.Context timer = metrics.timer("eventbus.dispatch").time();
        try {
            super.dispatch(event);
            metrics.counter("eventbus.success").inc();
        } catch (Exception e) {
            metrics.counter("eventbus.error").inc();
            throw e;
        } finally {
            timer.stop();
        }
    }
}
```

### 4. 性能优化技�?

#### 批量事件处理
```java
// 对于高频事件，考虑批量处理
@MassSubscribe
public void onMetricEvent(MetricEvent event) {
    // 收集到缓冲区
    metricBuffer.add(event);
    
    // 达到阈值时批量处理
    if (metricBuffer.size() >= BATCH_SIZE) {
        processBatch(new ArrayList<>(metricBuffer));
        metricBuffer.clear();
    }
}
```

#### 事件过滤
```java
@MassSubscribe
public void onDeviceEvent(DeviceOfflineEvent event) {
    // 只处理重要设备的事件
    if (!isImportantDevice(event.getDeviceId())) {
        return;  // 快速返回，减少不必要的处理
    }
    
    // 处理重要设备事件
    handleImportantDeviceOffline(event);
}
```

## 🔧 配置和调�?

### 1. Guava EventBus 调优
```java
// 自定义线程池大小
EventBusFacade eventBus = new GuavaEventBusFacade(
    Runtime.getRuntime().availableProcessors() * 2  // 根据CPU核数调整
);
```

### 2. Redis EventBus 调优
```java
// Redis连接优化
RedisConnectionManager.init(
    "redis-cluster.example.com",  // 使用Redis集群
    6379,
    "password",                   // 设置密码
    0,                           // 选择合适的数据�?
    Duration.ofSeconds(30),      // 连接超时
    Duration.ofSeconds(10)       // 读取超时
);

// Stream配置优化
RedisStreamEventBusFacade eventBus = new RedisStreamEventBusFacade(
    "mass_events_v2",           // 使用版本化的Stream�?
    "mass_consumer_group",      // 消费者组�?
    "instance-" + getInstanceId() // 实例特定的消费者名
);
```

### 3. 监控和诊�?

#### 性能监控
```java
// 监控事件处理性能
EventBusFacade eventBus = EventBusFactory.get("redis");
if (eventBus instanceof RedisStreamEventBusFacade) {
    RedisStreamEventBusFacade redisEventBus = (RedisStreamEventBusFacade) eventBus;
    
    log.info("EventBus状�? {}", redisEventBus.getStreamInfo());
    log.info("注册的监听器数量: {}", redisEventBus.getListenerCount());
}
```

#### 健康检�?
```java
@Component
public class EventBusHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        try {
            // 检查EventBus是否正常工作
            EventBusFacade eventBus = EventBusFactory.get("redis");
            // 发送测试事件并验证
            return Health.up()
                    .withDetail("type", "redis")
                    .withDetail("listeners", getListenerCount())
                    .build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
```

## 🧪 测试策略

### 1. 单元测试
```java
@Test
public void testEventDispatcher() {
    MassEventDispatcher dispatcher = new MassEventDispatcher();
    TestEventListener listener = new TestEventListener();
    
    dispatcher.registerListener(listener);
    
    DeviceOfflineEvent event = new DeviceOfflineEvent("device-001", "test", "trace");
    dispatcher.dispatch(event);
    
    assertEquals(1, listener.getEventCount());
}
```

### 2. 集成测试
```java
@Test
public void testRedisEventBus() {
    // 使用Testcontainers启动Redis
    try (RedisContainer redis = new RedisContainer("redis:6-alpine")) {
        redis.start();
        
        RedisConnectionManager.init(redis.getHost(), redis.getFirstMappedPort());
        EventBusFacade eventBus = EventBusFactory.get("redis");
        
        // 测试事件发布和接�?
        // ...
    }
}
```

### 3. 性能测试
```java
@Test
public void performanceTest() {
    EventBusFacade eventBus = EventBusFactory.get("guava");
    TestEventListener listener = new TestEventListener();
    eventBus.register(listener);
    
    int eventCount = 100_000;
    long startTime = System.nanoTime();
    
    for (int i = 0; i < eventCount; i++) {
        eventBus.post(new DeviceOfflineEvent("device-" + i, "test", "trace"));
    }
    
    long duration = System.nanoTime() - startTime;
    double avgLatency = duration / (double) eventCount / 1_000_000; // ms
    
    System.out.printf("处理%d个事件，平均延迟: %.3f ms%n", eventCount, avgLatency);
    assertTrue("平均延迟应小�?.01ms", avgLatency < 0.01);
}
```

## 📞 获取帮助

- **问题排查**: 查看日志输出，检查Redis连接状�?
- **性能问题**: 使用内置监控指标分析瓶颈
- **功能扩展**: 参考core/ARCHITECTURE.md了解扩展�?
- **最佳实�?*: 参考本文档的推荐做法部�?
