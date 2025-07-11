# Channel 模块设计文档

## 概述

Channel 模块提供了消息传输的抽象层，包含队列（Queue）、集合（Set）、映射（Map）和传输器（Transporter）等核心组件。该模块采用泛型设计，支持任意类型的消息传输，同时提供了线程安全的内存实现。

## 核心抽象

### 1. MessageQueue<T> - 消息队列抽象

消息队列提供 FIFO（先进先出）的消息存储和消费能力。

```java
public interface MessageQueue<T> {
    void offer(T message);                    // 投递消息
    T poll(long timeout, TimeUnit unit);      // 消费消息（带超时）
    boolean isEmpty();                        // 检查是否为空
    int size();                              // 获取队列大小
    String getName();                        // 获取队列名称
}
```

**特点：**
- 泛型设计，支持任意消息类型
- 阻塞式消费，支持超时控制
- 线程安全
- 可扩展实现（内存、数据库、外部系统等）

### 2. MessageSet<T> - 消息集合抽象

消息集合提供唯一元素的存储和基本集合操作。

```java
public interface MessageSet<T> {
    boolean add(T value);                // 添加元素
    boolean remove(T value);             // 移除元素
    boolean contains(T value);           // 是否包含元素
    int size();                          // 集合大小
    String getName();                    // 集合名称
}
```

**特点：**
- 泛型设计，支持任意元素类型
- 唯一性约束（不重复）
- 线程安全实现
- 支持自定义名称

### 3. MessageMap<K, V> - 消息映射抽象

消息映射提供基于键值对的消息存储和检索能力。

```java
public interface MessageMap<K, V> {
    void put(K key, V value);                // 存储键值对
    V get(K key);                           // 获取值
    V remove(K key);                        // 移除键值对
    boolean containsKey(K key);             // 检查键是否存在
    int size();                             // 获取映射大小
    String getName();                       // 获取映射名称
}
```

**特点：**
- 双泛型设计，支持任意键值类型
- 快速随机访问
- 线程安全
- 支持 null 键值处理

### 4. MessageQueueWithMap<K, V> - 双队列抽象

结合队列和映射的双重存储能力，提供更灵活的消息管理。

```java
public interface MessageQueueWithMap<K, V> extends MessageQueue<V>, MessageMap<K, V> {
    // 继承所有队列和映射方法
}
```

**特点：**
- 同时支持 FIFO 队列和键值映射
- 队列和映射操作相互独立
- 适用于需要快速查找的场景
- 提供 `getMapSize()` 方法获取映射大小

### 5. MessageTransporter<T> - 消息传输器抽象

消息传输器封装了消息的发送和接收逻辑，隐藏底层实现细节。

```java
public interface MessageTransporter<T> {
    void sendInput(T message);               // 发送输入消息
    T receiveInput(long timeout, TimeUnit unit); // 接收输入消息
    void sendOutput(T message);              // 发送输出消息
    T receiveOutput(long timeout, TimeUnit unit); // 接收输出消息
    int inputQueueSize();                    // 输入队列大小
    int outputQueueSize();                   // 输出队列大小
}
```

**特点：**
- 支持输入/输出双通道
- 统一的传输接口
- 可插拔实现（队列、API、多级队列等）
- 监控友好的设计

## 内存实现

### 1. InMemoryMessageQueue<T>

基于 `LinkedBlockingQueue` 的内存队列实现。

```java
// 使用示例
MessageQueue<String> queue = new InMemoryMessageQueue<>();
queue.offer("message1");
String message = queue.poll(1, TimeUnit.SECONDS);
```

**特性：**
- 无界队列，内存自动扩展
- 线程安全
- 高性能
- 支持阻塞操作

### 2. InMemoryMessageSet<T>

基于 `ConcurrentHashMap.newKeySet()` 的内存集合实现。

```java
// 使用示例
MessageSet<String> set = new InMemoryMessageSet<>();
set.add("msg1");
set.add("msg2");
boolean exists = set.contains("msg1");
set.remove("msg2");
int count = set.size();
```

**特性：**
- 唯一性保证
- 线程安全
- 高性能
- 支持自定义名称

### 3. InMemoryMessageMap<K, V>

基于 `ConcurrentHashMap` 的内存映射实现。

```java
// 使用示例
MessageMap<String, Integer> map = new InMemoryMessageMap<>();
map.put("key1", 100);
Integer value = map.get("key1");
```

**特性：**
- 线程安全
- 高性能随机访问
- 支持 null 键值
- 自动扩容

### 4. InMemoryMessageQueueWithMap<K, V>

结合队列和映射的双重实现。

```java
// 使用示例
MessageQueueWithMap<String, Integer> queueMap = new InMemoryMessageQueueWithMap<>();
queueMap.offer(100);           // 队列操作
queueMap.put("key1", 200);     // 映射操作
```

**特性：**
- 队列和映射独立操作
- 支持混合使用场景
- 提供 `getMapSize()` 方法

## 传输器实现

### 1. QueueBasedMessageTransporter<T>

基于队列的传输器实现。

```java
MessageQueue<T> inputQueue = new InMemoryMessageQueue<>();
MessageQueue<T> outputQueue = new InMemoryMessageQueue<>();
MessageTransporter<T> transporter = new QueueBasedMessageTransporter<>(inputQueue, outputQueue);
```

### 2. MultiLevelMessageTransporter<T>

多级队列传输器，支持消息优先级。

```java
MessageTransporter<T> transporter = new MultiLevelMessageTransporter<>();
```

### 3. ApiBasedMessageTransporter<T>

基于外部 API 的传输器实现。

```java
MessageTransporter<T> transporter = new ApiBasedMessageTransporter<>(
    "http://api.example.com/input",
    "http://api.example.com/output",
    "api-key"
);
```

## 工厂模式

### MessageTransporterFactory

提供统一的传输器创建接口。

```java
// 创建不同类型的传输器
MessageTransporter<Envelope> queueTransporter = MessageTransporterFactory.createQueueBased(inputQueue, outputQueue);
MessageTransporter<Envelope> multiLevelTransporter = MessageTransporterFactory.createMultiLevel();
MessageTransporter<Envelope> apiTransporter = MessageTransporterFactory.createApiBased(inputUrl, outputUrl, apiKey);
```

## 使用场景

### 1. 简单消息队列
```java
MessageQueue<Task> taskQueue = new InMemoryMessageQueue<>();
taskQueue.offer(new Task("task1"));
Task task = taskQueue.poll(1, TimeUnit.SECONDS);
```

### 2. 键值消息存储
```java
MessageMap<String, DeviceMessage> deviceMap = new InMemoryMessageMap<>();
deviceMap.put("device001", new DeviceMessage("status"));
DeviceMessage message = deviceMap.get("device001");
```

### 3. 混合存储场景
```java
MessageQueueWithMap<String, AssignmentRecord> assignmentQueue = new InMemoryMessageQueueWithMap<>();
assignmentQueue.offer(new AssignmentRecord());           // 队列操作
assignmentQueue.put("record001", new AssignmentRecord()); // 映射操作
```

### 4. 消息传输
```java
MessageTransporter<Envelope> transporter = MessageTransporterFactory.createQueueBased(inputQueue, outputQueue);
transporter.sendInput(envelope);
Envelope received = transporter.receiveInput(1, TimeUnit.SECONDS);
```

## 设计优势

1. **类型安全**：泛型设计确保编译时类型检查
2. **线程安全**：所有实现都是线程安全的
3. **可扩展性**：接口抽象支持多种实现
4. **性能优化**：基于高性能的并发数据结构
5. **监控友好**：提供大小、状态等监控信息
6. **易于测试**：清晰的接口设计便于单元测试

## 扩展指南

### 实现新的队列类型
```java
public class DatabaseMessageQueue<T> implements MessageQueue<T> {
    // 实现所有接口方法
}
```

### 实现新的传输器
```java
public class RedisMessageTransporter<T> implements MessageTransporter<T> {
    // 实现所有接口方法
}
```

### 实现新的映射类型
```java
public class FileMessageMap<K, V> implements MessageMap<K, V> {
    // 实现所有接口方法
}
```

## 测试覆盖

所有实现都包含完整的测试用例：
- 基本功能测试
- 并发安全测试
- 边界条件测试
- 性能测试
- 异常处理测试

---

## 2024-07-11 重大重构日志

### 1. 接口精简与职责分层
- 明确区分 Repository（数据层）与 Service（业务层），所有业务逻辑移至 Service 层，Repository 只做纯数据操作。
- 所有队列、Map、Set 等抽象均保持最小 API，避免冗余方法。

### 2. 统一注册机制
- 引入 EngineRegistry（原 TaskServiceRegistry），支持默认服务和自定义服务注册，key 由 project 改为 service 名称。
- 支持 TaskService、DeviceService 等多种服务的统一注册和获取。

### 3. 测试覆盖与集成
- 所有核心数据结构和服务均有单元测试和集成测试。
- 新增 DataFlowIntegrationTest，覆盖任务-设备-消息的完整流转。
- 测试用例全部适配最新接口，移除过时方法。

### 4. 文档与 TODO
- 新增/完善了 TODO.md，明确后续架构演进方向（状态机、事件驱动、监控、配置等）。
- channel_readme.md 文档同步更新，接口示例与实现说明与代码保持一致。

### 5. 兼容性与扩展性
- 保持所有接口向后兼容，老实现可平滑迁移。
- 支持多种队列/Map/Set/Transporter 实现，便于后续扩展。

---
