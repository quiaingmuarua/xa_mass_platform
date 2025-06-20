# MessageTransporter 重构说明

## 设计目标

通过引入 `MessageTransporter` 接口，隐藏内部队列实现细节，为后续升级为多级队列或外部API调用提供灵活性。

## 核心接口

### MessageTransporter
```java
public interface MessageTransporter {
    void sendInput(Envelope envelope);
    Envelope receiveInput(long timeout, TimeUnit unit) throws InterruptedException;
    
    void sendOutput(Envelope envelope);
    Envelope receiveOutput(long timeout, TimeUnit unit) throws InterruptedException;
    
    int inputQueueSize();
    int outputQueueSize();
}
```

## 实现类

### 1. QueueBasedMessageTransporter
- **用途**: 包装现有的 MessageQueue 实现
- **特点**: 向后兼容，直接使用内部队列
- **适用场景**: 当前系统，需要保持现有功能

### 2. MultiLevelMessageTransporter
- **用途**: 多级队列实现，支持消息优先级
- **特点**: 
  - 高优先级队列（PriorityBlockingQueue）
  - 普通优先级队列（LinkedBlockingQueue）
  - 低优先级队列（LinkedBlockingQueue）
- **适用场景**: 需要消息优先级处理的场景

### 3. ApiBasedMessageTransporter
- **用途**: 基于外部API的消息传输
- **特点**: 
  - 通过HTTP API发送/接收消息
  - 支持轮询机制
  - 可配置超时时间
- **适用场景**: 分布式系统，外部消息服务

## 使用示例

### 基本使用
```java
// 创建基于队列的传输器
MessageQueue<Envelope> inputQueue = new InMemoryMessageQueue<>();
MessageQueue<Envelope> outputQueue = new InMemoryMessageQueue<>();
MessageTransporter transporter = new QueueBasedMessageTransporter(inputQueue, outputQueue);

// 创建 DispatcherContext
DispatcherContext context = new DispatcherContext(transporter, sessionManager, gson);
```

### 使用工厂类
```java
// 基于队列
MessageTransporter transporter1 = MessageTransporterFactory.create(
    MessageTransporterFactory.TransporterType.QUEUE_BASED, 
    inputQueue, outputQueue
);

// 多级队列
MessageTransporter transporter2 = MessageTransporterFactory.create(
    MessageTransporterFactory.TransporterType.MULTI_LEVEL
);

// 基于API
MessageTransporter transporter3 = MessageTransporterFactory.create(
    MessageTransporterFactory.TransporterType.API_BASED,
    "http://api.example.com/input",
    "http://api.example.com/output", 
    "api-key"
);
```

## 升级路径

### 阶段1: 当前系统
- 使用 `QueueBasedMessageTransporter`
- 保持现有功能不变
- 所有代码通过 `MessageTransporter` 接口访问

### 阶段2: 多级队列
- 切换到 `MultiLevelMessageTransporter`
- 根据业务需求配置优先级规则
- 无需修改业务逻辑代码

### 阶段3: 外部API
- 切换到 `ApiBasedMessageTransporter`
- 配置外部API地址和认证信息
- 支持分布式部署

## 优势

1. **解耦**: 业务逻辑不依赖具体的队列实现
2. **可扩展**: 支持多种消息传输方式
3. **向后兼容**: 现有代码无需大幅修改
4. **可测试**: 便于单元测试和集成测试
5. **可配置**: 通过配置切换不同的实现

## 注意事项

1. 所有新的代码应该使用 `getMessageTransporter()` 方法
2. 旧的 `getInputQueue()` 和 `getOutputQueue()` 方法已标记为 `@Deprecated`
3. 外部API实现需要处理网络异常和重试机制
4. 多级队列需要根据业务需求定义优先级规则 