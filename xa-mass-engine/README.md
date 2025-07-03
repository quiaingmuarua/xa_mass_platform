# xa-mass-engine

本模块为业务核心模块，负责：

- 任务调度与分发（通过事件驱动）
- 设备管理与分配策略（通过事件驱动）
- 业务核心模型与服务

不包含启动入口，由 app 模块统一装配。

> 2024年6月起，所有 mock 相关流程、批量生成、全链路观测等能力已迁移至 `xa-mass-mock` 模块，engine 仅保留核心分配、规则链、模型与服务实现。

## 事件驱动说明

- 所有任务、设备相关事件均通过 `EventBusFacade` 注册和发布，与 gateway、mock、starter 等模块解耦。
- 典型事件如 `TaskCreatedEvent`、`TaskAuditedEvent`、`TaskAssignedEvent`、`DeviceOnlineBatchEvent` 等。
- 事件注册与发布示例见项目总 README。 