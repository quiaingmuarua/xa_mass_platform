# messaging 子模块

## 📖 定位

messaging 子模块聚合了所有基础消息通信模型，包括队列（MessageQueue）、流（MessageStream）、Map（MessageMap）、Set（MessageSet）等，统一接口抽象，支持多种后端实现（内存、Redis等）。

---

## 🏗️ 目录结构

```
messaging/
├── api/       # 核心接口
├── memory/    # 内存实现
├── redis/     # Redis实现
├── example/   # 示例
├── README.md  # 本文档
```

---

## ⚡ 核心接口

- `MessageQueue<T>`：基础队列模型，支持 offer/poll/ack/claim 等操作
- `MessageStream<T>`：流式消息模型，支持分组、消费组、阻塞拉取等
- `MessageMap<K,V>`、`MessageSet<T>`：Map/Set风格的消息存储
- 工厂类（如 MessageStreamFactory）支持一行切换实现

---

## 🚀 典型用法

```java
// 创建内存流
MessageStream<MyEvent> stream = MessageStreamFactory.create("memory", "my-stream", MyEvent.class);
// 创建Redis队列
MessageQueue<String> queue = MessageQueueFactory.create("redis", "my-queue", String.class);
// Map/Set同理
```

---

## 💡 扩展点

- 支持自定义 Provider，便于第三方扩展
- 批量/异步API，提升高吞吐场景效率
- 监控与可观测性，暴露统计与健康信息
- 消息过滤/路由，支持灵活分发策略

---

## 🔗 与 eventbus 的关系

- eventbus 推荐基于 MessageStream 实现（如 StreamEventBusFacade），实现解耦与可插拔
- messaging 可独立用于队列、流、Map/Set等多种场景

---

## 📚 更多

- [api/MessageStream.java](./api/MessageStream.java)
- [memory/InMemoryMessageStream.java](./memory/InMemoryMessageStream.java)
- [redis/LettuceRedisStream.java](./redis/LettuceRedisStream.java) 