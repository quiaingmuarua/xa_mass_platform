# TaskRepositoryManager 改进说明

## 问题背景

原始 `TaskRepositoryManager` 的 `createWithDefaultProjects` 方法存在设计不一致的问题：

1. **MessageStream 部分**：正确地根据 `QueueProviderType` 创建不同类型的实现
   - 使用 `MessageStreamProviderRegistry.createStreamWithDefaultGroup()` 
   - 支持 `IN_MEMORY`、`REDIS` 等不同类型

2. **MessageMap 部分**：硬编码使用 `InMemoryMessageMap`
   - 在 `registerAllProjects` 方法中直接使用 `new InMemoryMessageMap<>()`
   - 没有根据 `QueueProviderType` 进行选择

## 解决方案

### 1. 引入 MessageMapProviderRegistry

创建了 `MessageMapProviderRegistry` 类，类似于 `MessageStreamProviderRegistry`：

```java
public class MessageMapProviderRegistry {
    private static final ConcurrentMap<String, MessageMap<?, ?>> mapCache = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Function<String, MessageMap<?, ?>>> providers = new ConcurrentHashMap<>();
    
    // 支持根据 QueueProviderType 创建不同类型的 MessageMap
    public static <K, V> MessageMap<K, V> createMap(QueueProviderType type, String name)
}
```

### 2. 修改 TaskRepositoryManager

更新了 `TaskRepositoryManager` 的实现：

```java
// 任务实体操作
public void saveTask(Project project, TaskEntity taskEntity) {
    projectTaskMap.computeIfAbsent(project, k -> 
        MessageMapProviderRegistry.createMap(seedStreamType, "task:" + project.name()))
        .put(taskEntity.getTaskId(), taskEntity);
}

// 便捷构造器
public static TaskRepositoryManager createWithDefaultProjects(QueueProviderType seedStreamType, QueueProviderType msgStreamType) {
    TaskRepositoryManager manager = new TaskRepositoryManager(seedStreamType, msgStreamType);
    manager.registerAllProjects(project -> 
        MessageMapProviderRegistry.createMap(seedStreamType, "task:" + project.name()));
    return manager;
}
```

## 改进效果

### 1. 一致性
- MessageStream 和 MessageMap 现在都支持根据 `QueueProviderType` 动态选择实现
- 统一了配置方式，提高了代码一致性

### 2. 扩展性
- 支持 `IN_MEMORY`、`REDIS` 等多种实现
- 可以轻松添加新的 MessageMap 实现

### 3. 测试隔离
- 在测试中添加了 `MessageMapProviderRegistry.clearCache()` 调用
- 确保测试之间的状态隔离

## 使用示例

### 基本使用
```java
// 使用内存实现
TaskRepositoryManager memoryManager = TaskRepositoryManager.createWithDefaultProjects(
    QueueProviderType.IN_MEMORY, QueueProviderType.IN_MEMORY);

// 使用 Redis 实现（需要先初始化 Redis 连接）
TaskRepositoryManager redisManager = TaskRepositoryManager.createWithDefaultProjects(
    QueueProviderType.REDIS, QueueProviderType.REDIS);
```

### 混合使用
```java
// 种子流使用内存，消息流使用 Redis
TaskRepositoryManager hybridManager = TaskRepositoryManager.createWithDefaultProjects(
    QueueProviderType.IN_MEMORY, QueueProviderType.REDIS);
```

## 测试验证

1. **TaskRepositoryManagerTest**：所有22个测试通过
2. **MessageMapProviderRegistryTest**：新增的8个测试通过
3. **MessageMapProviderExample**：提供了完整的使用示例

## 向后兼容性

- 保持了原有的 API 接口不变
- 默认行为仍然是使用内存实现
- 现有代码无需修改即可继续工作

## 注意事项

1. **缓存机制**：`MessageMapProviderRegistry` 使用静态缓存，相同参数会返回相同实例
2. **测试清理**：测试中需要调用 `clearCache()` 确保状态隔离
3. **Redis 依赖**：使用 Redis 实现时需要先初始化 Redis 连接 