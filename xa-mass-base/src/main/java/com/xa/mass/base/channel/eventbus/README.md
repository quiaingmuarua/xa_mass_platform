# eventbus 子模块

## 📖 定位

eventbus 子模块提供统一的事件总线能力，支持本地/分布式事件驱动，推荐用 StreamEventBusFacade + MessageStream 实现，解耦底层消息流。

---

## 🏗️ 目录结构

```
eventbus/
├── core/       # 核心接口与分发器
├── example/    # 事件总线最佳实践
├── README.md   # 本文档
```

---

## 🚀 推荐用法

```java
// 创建消息流
MessageStream<MyEvent> stream = MessageStreamFactory.create("memory", "my-stream", MyEvent.class);
// 注入事件总线
StreamEventBusFacade eventBus = new StreamEventBusFacade(stream);
// 注册监听器
class MyListener {
    @MassSubscribe
    public void onMyEvent(MyEvent event) {
        log.info("收到事件: {}", event);
    }
}
eventBus.register(new MyListener());
// 发布事件
eventBus.post(new MyEvent(...));
```

---

## 💡 扩展点

- 支持多种消息流实现（内存、Redis、Kafka等）
- 支持批量、异步、过滤、分组等高级特性
- 监控与可观测性、异常隔离

---

## 📝 迁移说明

- 旧版 Guava/Redis 事件总线已 @Deprecated，推荐统一迁移到 StreamEventBusFacade + MessageStream

---

## 📚 更多

- [core/StreamEventBusFacade.java](./example/StreamEventBusFacade.java)
- [core/MassEventDispatcher.java](./core/MassEventDispatcher.java) 