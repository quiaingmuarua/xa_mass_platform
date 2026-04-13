# Channel 统一通信中台

## 📖 模块定位

Channel 模块是 Mass 平台的统一通信中台，聚合了队列（Queue）、流（Stream）、事件总线（EventBus）、传输器（Tranporter）等多种跨线程/跨模块通信能力，支持多种后端实现（内存、Redis、Kafka等），为业务层提供高性能、可扩展、可插拔的消息通信基础设施。

---

## 🏗️ 目录导航

```
channel/
├── messaging/      # 消息通信模型（队列、流、Map、Set等，含api/memory/redis等子目录）
├── eventbus/       # 事件总线相关实现（core/、example/等）
├── tranporter/     # 传输器/桥接器/适配器等
├── example/        # 统一示例
├── test/           # 统一测试
└── README.md       # 总体说明
```

---

## 🚀 典型用法

```java
// 创建消息流并注入事件总线
MessageStream<MyEvent> stream = MessageStreamFactory.create("memory", "my-stream", MyEvent.class);
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

- 支持自定义 Provider，便于第三方扩展
- 批量/异步API，提升高吞吐场景效率
- 监控与可观测性，暴露统计与健康信息
- 消息过滤/路由，支持灵活分发策略
- 分布式/多实例支持，适配大规模部署
- 安全/权限控制，支持加密与访问控制

---

## 📝 迁移说明

- 旧版 eventbus/queue 相关实现已标记为 @Deprecated，推荐统一迁移到 channel 体系下的新接口和实现
- 迁移只需替换工厂和门面类，业务代码基本无需改动

---

## 🗺️ 架构图建议

建议补充一张整体架构图，展示 messaging、eventbus、tranporter 之间的关系及与业务层的集成方式。

---

## 📚 子模块文档

- [messaging/README.md](./messaging/README.md)
- [eventbus/README.md](./eventbus/README.md)
- [tranporter/README.md](./tranporter/README.md)
- [example/README.md](./example/README.md)
- [test/README.md](./test/README.md) 