# 分发器上下文接口重构

## 概述

本次重构将原来的 `DispatcherContext` 类拆分为多个小的职责接口，提高了代码的灵活性和可测试性。

## 接口设计

### 核心接口

1. **SessionContext** - 会话管理上下文
   - `getSessionManager()` - 获取会话管理器

2. **CodecContext** - 编解码上下文
   - `getMessageCodec()` - 获取消息编解码器
   - 支持不同的编解码实现（如 Gson、Jackson、protobuf 等）

3. **TransportContext** - 消息传输上下文
   - `getMessageTransporter()` - 获取消息传输器

4. **HandlerRegistryContext** - 消息处理器注册表上下文
   - `getMessageHandlerRegistry()` - 获取消息处理器注册表
   - `setMessageHandlerRegistry()` - 设置消息处理器注册表

5. **MiddlewareContext** - 中间件上下文
   - `getDirection()` - 获取中间件方向
   - `setDirection()` - 设置中间件方向

### 组合接口

**DispatchRuntimeContext** - 完整的分发运行时上下文
- 继承所有上述接口
- 提供完整的分发运行时环境

## 实现类

**DispatcherContext** - 具体实现类
- 实现 `DispatchRuntimeContext` 接口
- 提供所有上下文功能的实现

## 编解码器架构

### MessageCodec 接口
- `encode(MassMessage)` - 编码消息为字符串
- `decode(String)` - 解码字符串为消息
- `isValid(String)` - 验证消息格式

### 实现类
- **GsonMessageCodec** - 基于 Gson 的实现
- **MessageCodecFactory** - 编解码器工厂，支持创建不同类型的编解码器

### 配置支持
- `MassApplicationConfig` 支持配置编解码器类型
- 支持自定义编解码器实例

## 使用方式

### 外部代码使用特定接口

```java
// 只需要会话管理功能
SessionContext sessionContext = DispatcherContextRegistry.getSessionContext();
ServerSessionManager sessionManager = sessionContext.getSessionManager();

// 只需要消息传输功能
TransportContext transportContext = DispatcherContextRegistry.getTransportContext();
MessageTransporter transporter = transportContext.getMessageTransporter();

// 只需要编解码功能
CodecContext codecContext = DispatcherContextRegistry.getCodecContext();
MessageCodec codec = codecContext.getMessageCodec();
String json = codec.encode(message);
MassMessage msg = codec.decode(json);
```

### 内部代码使用完整接口

```java
// 内部组件使用完整的分发运行时上下文
DispatchRuntimeContext context = DispatcherContextRegistry.get();
// 或者直接使用 DispatcherContext 实例
```

### 编解码器使用示例

```java
// 使用默认的 Gson 编解码器
MessageCodec codec = MessageCodecFactory.createDefault();

// 使用自定义 Gson 配置
MessageCodec codec = MessageCodecFactory.createGsonWithConfig();

// 通过配置创建
MassApplicationConfig config = new MassApplicationConfig();
config.setCodecType(MessageCodecFactory.CodecType.GSON);
MessageCodec codec = config.createMessageCodec();
```

## 优势

1. **接口隔离原则** - 外部代码只依赖它们真正需要的接口
2. **提高可测试性** - 可以轻松模拟特定的上下文接口
3. **增强灵活性** - 支持未来替换特定组件（如编解码器、传输器）
4. **向后兼容** - 保持现有 API 的兼容性
5. **清晰的职责分离** - 每个接口都有明确的职责
6. **编解码器抽象** - 支持多种编解码格式（JSON、protobuf、Avro 等）

## 迁移指南

### 控制器更新

- `SessionController` 使用 `SessionContext`
- `QueueController` 使用 `TransportContext`
- `MessageController` 使用 `TransportContext`
- `MetricsController` 使用 `TransportContext`
- `DebugController` 使用 `TransportContext`

### 中间件更新

- `EnvelopeMiddleware` 使用 `DispatchRuntimeContext`
- `ExceptionMiddleware` 使用 `DispatchRuntimeContext`

### 服务器组件更新

- `ServerMessageDispatcher` 使用 `DispatchRuntimeContext`
- `Engine` 使用 `DispatchRuntimeContext`
- `MassApplication` 使用 `DispatchRuntimeContext`
- `MassServerBuilder` 使用 `DispatchRuntimeContext`
- `MassServerConfig` 使用 `DispatchRuntimeContext`
- `MassServerStater` 使用 `DispatchRuntimeContext`
- `WebSocketServerImpl` 使用 `DispatchRuntimeContext`
- `DispatcherInboundHandler` 使用 `DispatchRuntimeContext`

### 编解码器更新

- `MessageParser` 使用 `MessageCodec` 接口
- 新增 `MessageCodecFactory` 工厂类
- 新增 `GsonMessageCodec` 实现类

## 注册表更新

`DispatcherContextRegistry` 现在提供多种获取方式：

```java
// 获取完整上下文
DispatchRuntimeContext context = DispatcherContextRegistry.get();

// 获取特定上下文
SessionContext sessionContext = DispatcherContextRegistry.getSessionContext();
TransportContext transportContext = DispatcherContextRegistry.getTransportContext();
CodecContext codecContext = DispatcherContextRegistry.getCodecContext();
HandlerRegistryContext handlerContext = DispatcherContextRegistry.getHandlerRegistryContext();
MiddlewareContext middlewareContext = DispatcherContextRegistry.getMiddlewareContext();
``` 