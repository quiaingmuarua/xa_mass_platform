# MessageStream 消息流抽象

MessageStream 是一个可靠的消息流抽象，支持消息的投递、消费、确认和认领操作。提供了内存和Redis Stream两种实现。

## 核心功能

### 基础操作
- **offer(T message)**: 投递消息到流中
- **poll(timeout, unit)**: 消费单条消息
- **pollBatch(batchSize, timeout, unit)**: 批量消费消息
- **ack(messageId)**: 确认消息已被成功处理
- **ackBatch(messageIds)**: 批量确认消息
- **claim(messageId, timeout, unit)**: 认领超时消息
- **size()**: 获取待处理消息数量
- **processingSize()**: 获取正在处理的消息数量
- **isEmpty()**: 检查流是否为空
- **getName()**: 获取流名称
- **getStats()**: 获取流统计信息
- **cleanupExpiredMessages()**: 清理过期消息

### 批量操作
MessageStream 提供了高效的批量操作支持：

```java
// 批量消费
List<StreamMessage<String>> messages = stream.pollBatch(10, 5, TimeUnit.SECONDS);

// 批量确认
List<String> messageIds = messages.stream()
    .map(StreamMessage::getMessageId)
    .collect(Collectors.toList());
int ackCount = stream.ackBatch(messageIds);
```

### 统计功能
通过 `getStats()` 方法可以获取流的详细统计信息：

```java
StreamStats stats = stream.getStats();
System.out.println("总消息数: " + stats.getTotalSize());
System.out.println("处理中消息数: " + stats.getProcessingSize());
System.out.println("待处理消息数: " + stats.getPendingSize());
System.out.println("流名称: " + stats.getStreamName());
System.out.println("统计时间: " + new Date(stats.getTimestamp()));
```

## 实现

### InMemoryMessageStream
基于内存的实现，使用 `LinkedBlockingQueue` 和 `ConcurrentHashMap` 提供线程安全的消息处理。

**特点：**
- 高性能，无网络延迟
- 支持消息过期清理
- 完整的ACK和Claim语义
- 适合单机应用或测试环境

**使用示例：**
```java
MessageStream<String> stream = new InMemoryMessageStream<>("my-stream");

// 投递消息
String messageId = stream.offer("Hello World");

// 消费消息
StreamMessage<String> message = stream.poll(5, TimeUnit.SECONDS);
if (message != null) {
    System.out.println("收到消息: " + message.getMessage());
    
    // 确认消息
    stream.ack(message.getMessageId());
}
```

### LettuceRedisStream
基于Redis Streams的实现，使用Lettuce客户端提供分布式消息处理。

**特点：**
- 分布式支持，多实例共享
- 持久化存储
- 消费者组支持
- 适合生产环境

**使用示例：**
```java
// 创建Redis连接
RedisClient client = RedisClient.create("redis://localhost:6379");
StatefulRedisConnection<String, String> connection = client.connect();
RedisStreamCommands<String, String> commands = connection.sync();

// 创建流
MessageStream<String> stream = new LettuceRedisStream<>(
    "my-redis-stream", 
    commands, 
    "my-group", 
    "my-consumer"
);

// 使用方式与内存实现相同
String messageId = stream.offer("Hello Redis");
StreamMessage<String> message = stream.poll(5, TimeUnit.SECONDS);
if (message != null) {
    stream.ack(message.getMessageId());
}
```

## 最佳实践

### 1. 批量处理
对于高吞吐量场景，建议使用批量操作：

```java
// 批量消费和处理
while (true) {
    List<StreamMessage<String>> batch = stream.pollBatch(100, 1, TimeUnit.SECONDS);
    if (batch.isEmpty()) {
        break;
    }
    
    // 处理消息
    List<String> successIds = new ArrayList<>();
    for (StreamMessage<String> msg : batch) {
        try {
            processMessage(msg.getMessage());
            successIds.add(msg.getMessageId());
        } catch (Exception e) {
            log.error("处理消息失败: " + msg.getMessageId(), e);
        }
    }
    
    // 批量确认成功的消息
    if (!successIds.isEmpty()) {
        stream.ackBatch(successIds);
    }
}
```

### 2. 错误处理
始终在try-catch块中处理消息，并正确确认消息：

```java
StreamMessage<String> message = stream.poll(5, TimeUnit.SECONDS);
if (message != null) {
    try {
        // 处理消息
        processMessage(message.getMessage());
        
        // 确认成功
        stream.ack(message.getMessageId());
    } catch (Exception e) {
        log.error("处理消息失败: " + message.getMessageId(), e);
        
        // 可以选择重新投递或删除消息
        // stream.ack(message.getMessageId()); // 删除失败的消息
    }
}
```

### 3. 监控和统计
定期检查流的状态和统计信息：

```java
// 定期监控
ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
scheduler.scheduleAtFixedRate(() -> {
    StreamStats stats = stream.getStats();
    log.info("流状态: {}", stats);
    
    // 检查是否有积压
    if (stats.getPendingSize() > 1000) {
        log.warn("消息积压严重: {}", stats.getPendingSize());
    }
}, 0, 30, TimeUnit.SECONDS);
```

### 4. 资源清理
在应用关闭时正确清理资源：

```java
// 清理过期消息
stream.cleanupExpiredMessages();

// 对于Redis实现，关闭连接
if (stream instanceof LettuceRedisStream) {
    // 关闭Redis连接
    connection.close();
    client.shutdown();
}
```

## 配置建议

### Redis Stream配置
```yaml
# application.yml
spring:
  redis:
    host: localhost
    port: 6379
    timeout: 5000ms
    lettuce:
      pool:
        max-active: 8
        max-idle: 8
        min-idle: 0
        max-wait: -1ms
```

### 消费者组配置
```java
// 建议的消费者组命名
String groupName = "app-name-" + environment;
String consumerName = "consumer-" + instanceId;

MessageStream<String> stream = new LettuceRedisStream<>(
    streamKey, 
    commands, 
    groupName, 
    consumerName
);
```

## 性能优化

1. **批量操作**: 使用 `pollBatch` 和 `ackBatch` 减少网络往返
2. **连接池**: 对于Redis实现，使用连接池管理连接
3. **超时设置**: 根据业务需求合理设置超时时间
4. **监控告警**: 设置积压监控和告警机制

## 故障处理

### 常见问题
1. **消息丢失**: 确保在消息处理完成后再调用ack
2. **重复消费**: 使用幂等性处理逻辑
3. **连接断开**: 实现重连机制和错误重试
4. **内存泄漏**: 定期清理过期消息

### 调试技巧
```java
// 启用详细日志
logging.level.com.xa.mass.base.channel.messaging=DEBUG

// 检查流状态
StreamStats stats = stream.getStats();
System.out.println("流统计: " + stats);

// 检查Redis Stream信息
// 使用Redis CLI: XINFO STREAM my-stream
``` 