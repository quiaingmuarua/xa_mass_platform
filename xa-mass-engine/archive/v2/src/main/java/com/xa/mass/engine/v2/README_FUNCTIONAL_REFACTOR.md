# 函数式队列提供者重构说明

## 重构背景

原始的工厂模式在支持多种队列类型（Redis、MQ、内存等）和多种数据结构（Queue、Map等）时变得臃肿。通过引入**函数式编程**思想，我们实现了更优雅、更灵活的队列创建机制。

## 核心设计

### 1. 函数式接口 `MessageQueueProvider<T>`

```java
@FunctionalInterface
public interface MessageQueueProvider<T> {
    MessageQueue<T> create(String name, Class<T> type);
}
```

**优势：**
- 支持 lambda 表达式，代码更简洁
- 类型安全，编译时检查
- 易于扩展和测试

### 2. 提供者注册表 `MessageQueueProviderRegistry`

```java
// 注册提供者
MessageQueue.register("memory", (queueKey, messageType, extraParams) -> new InMemoryMessageQueue<>());
MessageQueue.register("redis", (queueKey, messageType, extraParams) -> new RedisMessageQueue<>(queueKey));

// 创建队列
MessageQueue<String> queue = MessageQueue.createQueue("memory", "my-queue", String.class);
```

**优势：**
- 动态注册，无需修改核心代码
- 支持运行时切换实现
- 配置灵活，支持不同环境

## 使用方式

### 1. 基本使用

```java
// 使用默认内存队列
MessageQueue<String> queue = MessageQueue.createQueue("memory", "test-queue", String.class);
queue.offer("message");
```

### 2. 注册自定义提供者

```java
// 注册自定义提供者
MessageQueue.register("custom", (queueKey, messageType, extraParams) -> {
    logger.info("创建自定义队列: {}", queueKey);
    return new InMemoryMessageQueue<>();
});

// 使用自定义提供者
MessageQueue<String> queue = MessageQueue.createQueue("custom", "my-queue", String.class);
```

### 3. TaskRepositoryManager 集成

```java
// 使用默认配置
TaskRepositoryManager manager1 = new TaskRepositoryManager(taskMap);

// 使用自定义队列类型
TaskRepositoryManager manager2 = new TaskRepositoryManager(
    taskMap, 
    "memory",  // 种子队列类型
    "redis"    // 消息队列类型
);
```

## 与传统工厂模式对比

### 传统工厂模式（笨重）

```java
public enum QueueType {
    IN_MEMORY, REDIS, DATABASE, KAFKA, RABBITMQ
}

public static <T> MessageQueue<T> create(QueueType type, Object... params) {
    switch (type) {
        case IN_MEMORY:
            return new InMemoryMessageQueue<>();
        case REDIS:
            return new RedisMessageQueue(params[0]);
        case DATABASE:
            return new DatabaseMessageQueue(params[0], params[1]);
        // ... 更多 case
        default:
            throw new IllegalArgumentException("不支持的队列类型: " + type);
    }
}
```

**问题：**
- 每次新增类型都要修改 switch 语句
- 参数传递复杂，类型不安全
- 难以进行单元测试
- 代码臃肿，维护困难

### 函数式提供者模式（优雅）

```java
// 注册提供者（一次注册，到处使用）
MessageQueue.register("memory", (queueKey, messageType, extraParams) -> new InMemoryMessageQueue<>());
MessageQueue.register("redis", (queueKey, messageType, extraParams) -> new RedisMessageQueue<>(queueKey));
MessageQueue.register("database", (queueKey, messageType, extraParams) -> new DatabaseMessageQueue<>(queueKey));

// 使用（简洁明了）
MessageQueue<String> queue = MessageQueue.createQueue("memory", "my-queue", String.class);
```

**优势：**
- 新增类型只需注册，无需修改核心代码
- 参数传递简单，类型安全
- 易于测试和 Mock
- 代码简洁，维护简单

## 扩展性示例

### 1. 添加 Redis 队列

```java
// 注册 Redis 提供者
MessageQueue.register("redis", (queueKey, messageType, extraParams) -> {
    return new RedisMessageQueue<>(queueKey, redisConfig);
});

// 使用 Redis 队列
MessageQueue<String> queue = MessageQueue.createQueue("redis", "user-queue", String.class);
```

### 2. 添加条件化提供者

```java
// 根据名称创建不同类型的队列
MessageQueue.register("smart", (queueKey, messageType, extraParams) -> {
    if (queueKey.contains("high-priority")) {
        return new PriorityMessageQueue<>();
    } else if (queueKey.contains("persistent")) {
        return new PersistentMessageQueue<>();
    } else {
        return new InMemoryMessageQueue<>();
    }
});
```

### 3. 添加配置化提供者

```java
// 从配置文件读取队列类型
MessageQueue.register("config", (queueKey, messageType, extraParams) -> {
    String queueType = config.getProperty("queue." + queueKey + ".type", "memory");
    return MessageQueue.createQueue(queueType, queueKey, messageType);
});
```

## 测试支持

### 1. 单元测试

```java
@Test
public void testWithMockQueue() {
    // 注册 Mock 提供者
    MessageQueue.register("mock", (queueKey, messageType, extraParams) -> mock(MessageQueue.class));
    
    // 测试
    MessageQueue<String> queue = MessageQueue.createQueue("mock", "test", String.class);
    // ... 测试逻辑
}
```

### 2. 集成测试

```java
@Test
public void testWithRealQueue() {
    // 使用真实的 Redis 队列
    MessageQueue<String> queue = MessageQueue.createQueue("redis", "test", String.class);
    // ... 集成测试逻辑
}
```

## 性能优势

### 1. 延迟创建

```java
// 提供者只在需要时才创建队列
MessageQueue.register("lazy", (queueKey, messageType, extraParams) -> {
    // 这里可以进行复杂的初始化逻辑
    return new LazyInitializedQueue<>(queueKey);
});
```

### 2. 缓存支持

```java
// 支持队列缓存
MessageQueue.register("cached", (queueKey, messageType, extraParams) -> {
    return queueCache.computeIfAbsent(queueKey, k -> new InMemoryMessageQueue<>());
});
```

## 配置管理

### 1. 环境配置

```java
// 开发环境
if (isDevelopment()) {
    MessageQueue.register("default", (queueKey, messageType, extraParams) -> new InMemoryMessageQueue<>());
}

// 生产环境
if (isProduction()) {
    MessageQueue.register("default", (queueKey, messageType, extraParams) -> new RedisMessageQueue<>(queueKey));
}
```

### 2. 动态配置

```java
// 支持运行时切换队列类型
public void switchQueueType(String taskId, String newType) {
    MessageQueue<String> newQueue = MessageQueue.createQueue(newType, taskId, String.class);
    // 迁移数据并替换队列
}
```

## 总结

通过函数式重构，我们实现了：

1. **更简洁的代码**：lambda 表达式替代复杂的 switch 语句
2. **更好的扩展性**：新增队列类型只需注册，无需修改核心代码
3. **更强的类型安全**：编译时类型检查，减少运行时错误
4. **更灵活的配置**：支持动态注册和运行时切换
5. **更好的测试性**：易于 Mock 和单元测试
6. **更高的性能**：支持延迟创建和缓存

这种设计模式特别适合需要支持多种队列实现和配置的场景，让代码更加优雅和可维护。 