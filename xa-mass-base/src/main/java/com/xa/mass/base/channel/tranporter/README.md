# tranporter 子模块

Status: current local package orientation note.

## 📖 定位

tranporter 子模块用于实现跨模块、跨协议、跨服务的消息传输与桥接能力，适合异构系统集成、协议适配、消息桥接等场景。

---

## 🏗️ 目录结构

```
tranporter/
├── ...         # 具体实现
├── README.md   # 本文档
```

---

## 🚀 典型场景

- 跨服务/跨进程消息桥接（如 Redis <-> Kafka、内存 <-> Redis）
- 协议适配（如 HTTP <-> MQ、gRPC <-> Stream）
- 业务解耦、异构系统集成

---

## 💡 扩展点

- 支持多种协议/后端适配
- 支持消息转换、过滤、路由
- 支持监控与健康检查

---

## 🔗 与 messaging/eventbus 的关系

- 可作为 messaging/eventbus 的桥接器，实现不同消息模型/后端间的互通
- 便于实现复杂的消息流转、数据同步、系统集成
