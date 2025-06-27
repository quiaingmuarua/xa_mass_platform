# TaskManager、DeviceManager 和 RuleManager 存储层重构说明

## 重构背景

原始的 `TaskManager`、`DeviceManager` 和 `RuleManager` 直接使用 `ConcurrentHashMap` 进行内存存储，存在以下问题：

1. **紧耦合**：Manager 类与具体存储实现紧密耦合
2. **扩展性差**：无法轻松切换到其他存储后端（如 Redis、数据库）
3. **代码重复**：存储逻辑与业务逻辑混合在一起
4. **测试困难**：难以进行单元测试和集成测试

## 重构方案

### 1. 抽象存储接口

#### TaskStorage 接口
创建了 `TaskStorage` 接口，定义了任务和任务消息的存储抽象：

```java
public interface TaskStorage {
    void saveTask(Task task);
    Optional<Task> getTask(String taskId);
    boolean updateTask(Task task);
    boolean deleteTask(String taskId);
    List<Task> getAllTasks();
    List<Task> getTasksByStatus(String status);
    List<Task> getSchedulableTasks();
    void addTaskMessage(String taskId, TaskMsg taskMsg);
    List<TaskMsg> getTaskMessages(String taskId);
    TaskMessageStats getTaskMessageStats(String taskId);
}
```

#### DeviceStorage 接口
创建了 `DeviceStorage` 接口，定义了设备和Token的存储抽象：

```java
public interface DeviceStorage {
    void addDevice(Device device);
    Optional<Device> getDevice(String deviceId);
    boolean updateDevice(Device device);
    boolean deleteDevice(String deviceId);
    List<Device> getDevicesByCountry(String country);
    List<Device> getAllDevices();
    void addToken(String deviceId, Token token);
    Optional<Token> getToken(String deviceId);
    boolean updateToken(String deviceId, Token token);
    boolean deleteToken(String deviceId);
    List<Token> getAllTokens();
    boolean tryLockDevice(String deviceId);
    void unlockDevice(String deviceId);
    boolean isLocked(String deviceId);
    List<String> getLockedDevices();
}
```

#### RuleStorage 接口
创建了 `RuleStorage` 接口，定义了规则定义和规则评估器的存储抽象：

```java
public interface RuleStorage {
    void addRule(RuleDefinition rule);
    Optional<RuleDefinition> getRule(String ruleId);
    boolean updateRule(RuleDefinition rule);
    boolean deleteRule(String ruleId);
    List<RuleDefinition> getAllRules();
    List<RuleDefinition> getRulesByType(RuleType ruleType);
    void addRules(Collection<RuleDefinition> rules);
    void deleteRules(Collection<String> ruleIds);
    void registerEvaluator(RuleType ruleType, RuleEvaluator evaluator);
    Optional<RuleEvaluator> getEvaluator(RuleType ruleType);
    List<RuleType> getRegisteredEvaluatorTypes();
    boolean removeEvaluator(RuleType ruleType);
    void clear();
}
```

### 2. 存储实现

#### 内存存储实现
- **InMemoryTaskStorage**: 将原来的Map逻辑封装到实现类中
- **InMemoryDeviceStorage**: 将原来的Map逻辑封装到实现类中
- **InMemoryRuleStorage**: 将原来的Map逻辑封装到实现类中
- 保持线程安全（使用 ConcurrentHashMap 和 Collections.synchronizedSet）
- 作为默认存储实现

#### Redis 存储实现
- **RedisTaskStorage**: 提供 Redis 存储的示例实现
- **RedisDeviceStorage**: 提供 Redis 存储的示例实现
- **RedisRuleStorage**: 提供 Redis 存储的示例实现
- 使用 JSON 序列化存储数据
- 支持按状态索引查询

### 3. 存储工厂 (`TaskStorageFactory`)

提供统一的存储创建入口，支持任务存储、设备存储和规则存储：

```java
// 创建任务存储
TaskStorage taskStorage = TaskStorageFactory.createDefaultTaskStorage();
TaskStorage redisTaskStorage = TaskStorageFactory.createTaskStorage(StorageType.REDIS);

// 创建设备存储
DeviceStorage deviceStorage = TaskStorageFactory.createDefaultDeviceStorage();
DeviceStorage redisDeviceStorage = TaskStorageFactory.createDeviceStorage(StorageType.REDIS);

// 创建规则存储
RuleStorage ruleStorage = TaskStorageFactory.createDefaultRuleStorage();
RuleStorage redisRuleStorage = TaskStorageFactory.createRuleStorage(StorageType.REDIS);

// 通过配置字符串创建
TaskStorage configTaskStorage = TaskStorageFactory.createTaskStorage("memory");
DeviceStorage configDeviceStorage = TaskStorageFactory.createDeviceStorage("memory");
RuleStorage configRuleStorage = TaskStorageFactory.createRuleStorage("memory");
```

### 4. Manager 类重构

#### TaskManager 重构
重构后的 `TaskManager`：

```java
public class TaskManager {
    private final TaskStorage taskStorage;
    private final TaskScheduler taskScheduler;
    
    // 使用默认存储
    public TaskManager(TaskScheduler taskScheduler) {
        this(taskScheduler, TaskStorageFactory.createDefaultTaskStorage());
    }
    
    // 使用自定义存储
    public TaskManager(TaskScheduler taskScheduler, TaskStorage taskStorage) {
        this.taskScheduler = taskScheduler;
        this.taskStorage = taskStorage;
    }
    
    // 所有存储操作都委托给 taskStorage
    public Task getTask(String taskId) {
        return taskStorage.getTask(taskId).orElse(null);
    }
    
    public boolean updateTask(Task task) {
        return taskStorage.updateTask(task);
    }
    // ... 其他方法
}
```

#### DeviceManager 重构
重构后的 `DeviceManager`：

```java
public class DeviceManager {
    private final DeviceStorage deviceStorage;
    
    // 使用默认存储
    public DeviceManager() {
        this(TaskStorageFactory.createDefaultDeviceStorage());
    }
    
    // 使用自定义存储
    public DeviceManager(DeviceStorage deviceStorage) {
        this.deviceStorage = deviceStorage;
    }
    
    // 所有存储操作都委托给 deviceStorage
    public void addDevice(Device device) {
        deviceStorage.addDevice(device);
    }
    
    public Device getDevice(String deviceId) {
        return deviceStorage.getDevice(deviceId).orElse(null);
    }
    
    public List<Device> getDevicesByCountry(String country) {
        return deviceStorage.getDevicesByCountry(country);
    }
    // ... 其他方法
}
```

#### RuleManager 重构
重构后的 `RuleManager`：

```java
public class RuleManager<T> {
    private final RuleStorage ruleStorage;
    
    // 使用默认存储
    public RuleManager() {
        this(TaskStorageFactory.createDefaultRuleStorage());
    }
    
    // 使用自定义存储
    public RuleManager(RuleStorage ruleStorage) {
        this.ruleStorage = ruleStorage;
    }
    
    // 所有存储操作都委托给 ruleStorage
    public void addDefaultRule(RuleDefinition rule) {
        ruleStorage.addRule(rule);
    }
    
    public Optional<RuleDefinition> getRule(String ruleId) {
        return ruleStorage.getRule(ruleId);
    }
    
    public List<RuleDefinition> getDefaultRules() {
        return ruleStorage.getAllRules();
    }
    
    public boolean evaluate(RuleDefinition rule, T context) throws Exception {
        Optional<RuleEvaluator> evaluatorOpt = ruleStorage.getEvaluator(rule.getType());
        if (evaluatorOpt.isEmpty()) {
            throw new IllegalArgumentException("不支持的规则类型:" + rule.getType());
        }
        return evaluatorOpt.get().evaluate(rule, context);
    }
    // ... 其他方法
}
```

## 使用方式

### 1. 使用默认内存存储

```java
// TaskManager
TaskScheduler scheduler = new SimpleTaskScheduler();
TaskManager taskManager = new TaskManager(scheduler); // 自动使用内存存储

// DeviceManager
DeviceManager deviceManager = new DeviceManager(); // 自动使用内存存储

// RuleManager
RuleManager<Map<String, Object>> ruleManager = new RuleManager<>(); // 自动使用内存存储
```

### 2. 使用自定义存储

```java
// TaskManager
TaskScheduler scheduler = new SimpleTaskScheduler();
TaskStorage redisTaskStorage = TaskStorageFactory.createTaskStorage(StorageType.REDIS);
TaskManager taskManager = new TaskManager(scheduler, redisTaskStorage);

// DeviceManager
DeviceStorage redisDeviceStorage = TaskStorageFactory.createDeviceStorage(StorageType.REDIS);
DeviceManager deviceManager = new DeviceManager(redisDeviceStorage);

// RuleManager
RuleStorage redisRuleStorage = TaskStorageFactory.createRuleStorage(StorageType.REDIS);
RuleManager<Map<String, Object>> ruleManager = new RuleManager<>(redisRuleStorage);
```

### 3. 通过配置切换存储

```java
// 从配置文件读取存储类型
String taskStorageType = config.getProperty("task.storage.type", "memory");
String deviceStorageType = config.getProperty("device.storage.type", "memory");
String ruleStorageType = config.getProperty("rule.storage.type", "memory");

TaskStorage taskStorage = TaskStorageFactory.createTaskStorage(taskStorageType);
DeviceStorage deviceStorage = TaskStorageFactory.createDeviceStorage(deviceStorageType);
RuleStorage ruleStorage = TaskStorageFactory.createRuleStorage(ruleStorageType);

TaskManager taskManager = new TaskManager(scheduler, taskStorage);
DeviceManager deviceManager = new DeviceManager(deviceStorage);
RuleManager<Map<String, Object>> ruleManager = new RuleManager<>(ruleStorage);
```

## 扩展新的存储实现

要添加新的存储实现，只需：

1. 实现对应的存储接口（`TaskStorage`、`DeviceStorage` 或 `RuleStorage`）
2. 在 `TaskStorageFactory` 中添加新的存储类型
3. 在工厂方法中创建对应的实例

例如，添加数据库存储：

```java
public class DatabaseTaskStorage implements TaskStorage {
    // 实现所有接口方法
}

public class DatabaseDeviceStorage implements DeviceStorage {
    // 实现所有接口方法
}

public class DatabaseRuleStorage implements RuleStorage {
    // 实现所有接口方法
}

// 在 TaskStorageFactory 中
public static TaskStorage createTaskStorage(StorageType type) {
    switch (type) {
        case MEMORY:
            return new InMemoryTaskStorage();
        case REDIS:
            return new RedisTaskStorage();
        case DATABASE:
            return new DatabaseTaskStorage(); // 新增
        default:
            throw new IllegalArgumentException("Unsupported storage type: " + type);
    }
}

public static DeviceStorage createDeviceStorage(StorageType type) {
    switch (type) {
        case MEMORY:
            return new InMemoryDeviceStorage();
        case REDIS:
            return new RedisDeviceStorage();
        case DATABASE:
            return new DatabaseDeviceStorage(); // 新增
        default:
            throw new IllegalArgumentException("Unsupported storage type: " + type);
    }
}

public static RuleStorage createRuleStorage(StorageType type) {
    switch (type) {
        case MEMORY:
            return new InMemoryRuleStorage();
        case REDIS:
            return new RedisRuleStorage();
        case DATABASE:
            return new DatabaseRuleStorage(); // 新增
        default:
            throw new IllegalArgumentException("Unsupported storage type: " + type);
    }
}
```

## 优势

1. **解耦**：Manager 类不再依赖具体存储实现
2. **可扩展**：轻松添加新的存储后端
3. **可测试**：可以轻松进行单元测试和集成测试
4. **配置化**：可以通过配置切换存储实现
5. **向后兼容**：保持原有 API 不变
6. **统一架构**：TaskManager、DeviceManager 和 RuleManager 使用相同的存储抽象模式
7. **类型安全**：使用泛型保证类型安全

## 注意事项

1. **线程安全**：所有存储实现都应该保证线程安全
2. **性能考虑**：不同存储实现的性能特征不同，需要根据实际需求选择
3. **数据一致性**：在分布式环境下需要特别注意数据一致性问题
4. **错误处理**：存储操作可能失败，需要适当的错误处理机制
5. **向后兼容**：保留了原有的工厂方法，但标记为 @Deprecated
6. **评估器序列化**：Redis存储中的评估器序列化需要特别注意，可能需要使用工厂模式

## 新增文件列表

### 任务存储相关
- `TaskStorage.java` - 任务存储接口
- `InMemoryTaskStorage.java` - 内存任务存储实现
- `RedisTaskStorage.java` - Redis任务存储示例实现

### 设备存储相关
- `DeviceStorage.java` - 设备存储接口
- `InMemoryDeviceStorage.java` - 内存设备存储实现
- `RedisDeviceStorage.java` - Redis设备存储示例实现

### 规则存储相关
- `RuleStorage.java` - 规则存储接口
- `InMemoryRuleStorage.java` - 内存规则存储实现
- `RedisRuleStorage.java` - Redis规则存储示例实现

### 工厂和示例
- `TaskStorageFactory.java` - 存储工厂（支持任务、设备和规则存储）
- `StorageExample.java` - 任务存储使用示例
- `DeviceStorageExample.java` - 设备存储使用示例
- `RuleStorageExample.java` - 规则存储使用示例

### 重构的类
- `TaskManager.java` - 重构后使用TaskStorage接口
- `DeviceManager.java` - 重构后使用DeviceStorage接口
- `RuleManager.java` - 重构后使用RuleStorage接口 