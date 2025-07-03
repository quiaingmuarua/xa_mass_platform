# xa-mass-gateway

本模块为消息网关与协议适配层，负责：

- WebSocket 连接管理
- 消息分发与中间件链
- 协议适配与消息编解码
- 会话与连接上下文管理
- 设备上下线等事件通过 eventbus 统一驱动

不包含启动入口，由 app 模块统一装配。

> 端到端 mock、集成测试能力由 `xa-mass-mock` 模块统一提供。

## 事件驱动说明

- 设备上下线、消息分发等事件均通过 `EventBusFacade` 注册和发布，与 engine、mock、starter 等模块解耦。
- 典型事件如 `DeviceOnlineBatchEvent`、`DeviceOfflineSingleEvent` 等。 