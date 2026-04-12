# xa-mass-gateway

## Agent Notes

- Current role: WebSocket/session/message dispatch layer
- Current runtime validation: verified only as part of full startup via `xa-mass-mock`
- Do not assume this module has a separately verified standalone boot path
- For verified commands and runtime behavior, read:
  - [`../AGENTS.md`](../AGENTS.md)
  - [`../doc/AGENT_BASELINE.md`](../doc/AGENT_BASELINE.md)
  - [`../doc/VERIFIED_RUNBOOK.md`](../doc/VERIFIED_RUNBOOK.md)

## Start Here

Open these first if you are debugging WebSocket/session behavior:

- `src/main/java/com/xa/mass/gateway/server/WebSocketServerImpl.java`
- `src/main/java/com/xa/mass/gateway/dispatcher/ServerMessageDispatcher.java`

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
