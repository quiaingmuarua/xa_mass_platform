# TaskRepositoryManager 队列优化说明

## 优化背景

原始的 `TaskRepositoryManager` 中硬编码了 `InMemoryMessageQueue`，这导致：

1. **缺乏灵活性**：无法根据环境或需求切换不同的队列实现
2. **扩展性差**：难以支持 Redis、数据库等外部队列
3. **测试困难**：难以进行单元测试和集成测试
4. **配置复杂**：无法通过配置来管理队列类型

## 优化方案

### 1. 引入工厂模式

创建了 `MessageQueueFactory` 类，支持创建不同类型的队列：

```java
// 创建内存队列
MessageQueue<String> queue1 = MessageQueueFactory.createInMemory();

// 根据类型创建队列
MessageQueue<String> queue2 = MessageQueueFactory.create(MessageQueueFactory.QueueType.IN_MEMORY);

// 创建默认队列
MessageQueue<String> queue3 = MessageQueueFactory.createDefault();
```

### 2. 配置化管理

创建了 `QueueConfig` 类，支持细粒度的队列配置：

```java
QueueConfig config = new QueueConfig();
config.setTaskSeedQueueType(MessageQueueFactory.QueueType.IN_MEMORY);
config.setTaskMsgQueueType(MessageQueueFactory.QueueType.IN_MEMORY);
```

### 3. 环境预设

提供了不同环境的预设配置：

```java
// 开发环境
QueueConfig devConfig = QueueConfig.createDevelopment();

// 测试环境
QueueConfig testConfig = QueueConfig.createTest();

// 生产环境
QueueConfig prodConfig = QueueConfig.createProduction();
```

## 使用方式

### 1. 默认使用（推荐）

```java
MessageMap<String, TaskEntity> taskMap = new InMemoryMessageMap<>();
TaskRepositoryManager manager = new TaskRepositoryManager(taskMap);
// 自动使用内存队列
```

### 2. 指定队列类型

```java
TaskRepositoryManager manager = new TaskRepositoryManager(
    taskMap, 
    MessageQueueFactory.QueueType.IN_MEMORY
);
```

### 3. 使用详细配置

```java
QueueConfig config = new QueueConfig();
config.setTaskSeedQueueType(MessageQueueFactory.QueueType.IN_MEMORY);
config.setTaskMsgQueueType(MessageQueueFactory.QueueType.IN_MEMORY);

TaskRepositoryManager manager = new TaskRepositoryManager(taskMap, config);
```

### 4. 环境配置

```java
// 开发环境
TaskRepositoryManager devManager = new TaskRepositoryManager(
    taskMap, 
    QueueConfig.createDevelopment()
);

// 生产环境
TaskRepositoryManager prodManager = new TaskRepositoryManager(
    taskMap, 
    QueueConfig.createProduction()
);
```

## 扩展性

### 1. 添加新的队列类型

在 `MessageQueueFactory.QueueType` 枚举中添加新类型：

```java
public enum QueueType {
    IN_MEMORY,      // 内存队列
    REDIS,          // Redis队列
    DATABASE,       // 数据库队列
    KAFKA,          // Kafka队列（新增）
    RABBITMQ        // RabbitMQ队列（新增）
}
```

### 2. 实现新的队列

创建新的队列实现类：

```java
public class KafkaMessageQueue<T> implements MessageQueue<T> {
    // 实现 MessageQueue 接口
}
```

### 3. 在工厂中注册

在 `MessageQueueFactory.create()` 方法中添加新类型的处理：

```java
case KAFKA:
    return new KafkaMessageQueue<>();
```

## 优势

### 1. 解耦设计

- 业务逻辑与队列实现分离
- 支持运行时切换队列类型
- 便于单元测试

### 2. 配置灵活

- 支持不同环境使用不同队列
- 支持细粒度配置
- 支持动态配置

### 3. 扩展性强

- 易于添加新的队列实现
- 支持多种队列类型
- 支持混合使用

### 4. 向后兼容

- 保持原有 API 不变
- 默认行为保持一致
- 平滑升级

## 测试支持

### 1. 单元测试

```java
@Test
public void testWithMockQueue() {
    MessageQueue<String> mockQueue = mock(MessageQueue.class);
    // 使用 Mock 队列进行测试
}
```

### 2. 集成测试

```java
@Test
public void testWithRedisQueue() {
    QueueConfig config = new QueueConfig();
    config.setTaskSeedQueueType(MessageQueueFactory.QueueType.REDIS);
    // 使用 Redis 队列进行集成测试
}
```

## 未来规划

### 1. 短期目标

- [ ] 实现 Redis 队列
- [ ] 实现数据库队列
- [ ] 添加队列监控

### 2. 中期目标

- [ ] 支持队列集群
- [ ] 支持队列持久化
- [ ] 支持队列备份

### 3. 长期目标

- [ ] 支持分布式队列
- [ ] 支持队列流式处理
- [ ] 支持队列事件驱动

## 注意事项

1. **性能考虑**：不同队列实现的性能差异较大，需要根据实际场景选择
2. **数据一致性**：外部队列需要考虑数据一致性问题
3. **错误处理**：需要处理队列连接失败、超时等异常情况
4. **监控告警**：建议添加队列监控和告警机制 