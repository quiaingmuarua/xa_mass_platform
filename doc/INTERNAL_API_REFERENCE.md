# XA Mass Platform Internal API Reference

> 最后核实：2026-04-13
>
> 每个接口均标注了**当前实现状�?*�?> - 🟢 **已实�?* �?调用会返回真实数�?> - 🟡 **部分实现** �?接口存在，但返回数据为快�?占位
> - 🔴 **未实�?* �?接口不存在，调用�?404
>
> 文档范围�?> - HTTP / WebSocket 接口清单
> - 当前实现状�?> - 返回格式与状态约�?>
> 不负责记录：
> - 启动命令
> - 端到端运行链�?> - 回归测试命令
>
> 这些内容统一�?[VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md)

---

## 1. 队列监控 🟢 已实�?

- **路径**：`GET /api/queue/status`
- **实现�?*：`QueueController.getQueueStatus()`
- **实测返回**�?

```json
{
  "success": true,
  "data": {
    "inputQueue": 0,
    "outputQueue": 0
  }
}
```

> 当前为内存队列，mock 启动后通常�?0（消息被快速消费）�?

- **路径**：`GET /api/queue/detail`

```json
{
  "success": true,
  "data": {
    "inputQueueSize": 0,
    "outputQueueSize": 0,
    "transporterAvailable": true
  }
}
```

---

## 2. Session 管理 🟢 已实现（返回真实数据�?

- **路径**：`GET /api/session/list`
- **实现�?*：`SessionController.listSessions()`
- **返回示例**（有 WebSocket 连接时）�?

```json
{
  "success": true,
  "data": [
    {
      "deviceId": "dev123",
      "connections": [
        {
          "role": "task",
          "active": true,
          "channelId": "abc123"
        }
      ]
    }
  ]
}
```

> 无连接时 `data` 为空数组 `[]`，不�?NPE�?

- **路径**：`GET /api/session/stats`
- **返回示例**�?

```json
{
  "success": true,
  "data": {
    "activeConnections": 2,
    "deviceCount": 1
  }
}
```

---

## 3. 任务管理 🟢 已实�?

基路径：`/status/api/tasks`

| 方法 | 路径 | 功能 | 注意 |
|------|------|------|------|
| `POST` | `/status/api/tasks` | 创建任务 | 初始状态为 `NEW` |
| `GET` | `/status/api/tasks/{taskId}` | 查询任务 + targetList | �?|
| `PUT` | `/status/api/tasks/{taskId}` | 更新任务基本信息 | 不含状态变�?|
| `DELETE` | `/status/api/tasks/{taskId}` | 删除任务 | **�?NEW / TERMINAL 可删** |
| `POST` | `/status/api/tasks/{taskId}/audit` | 审核（approve/reject�?| `approved=true`: `NEW/BLOCKED -> READY`; `approved=false`: `NEW -> BLOCKED` |
| `POST` | `/status/api/tasks/{taskId}/pause` | 暂停 | `READY/RUNNING -> PAUSED` |
| `POST` | `/status/api/tasks/{taskId}/resume` | 恢复 | �?PAUSED 可恢�?|
| `POST` | `/status/api/tasks/{taskId}/terminate` | 终止 | 任意�?TERMINAL 状�?|
| `PUT` | `/status/api/tasks/{taskId}/status` | 状态路由辅助接�?| 通过 status 参数转发�?`approve/reject/pause/resume/cancel` |
| `GET` | `/status/api/tasks/{taskId}/messages` | 分页获取 TaskMsg | `?page=1&size=20` |

### 任务状态机

```
NEW ──approve──�?READY ──pause──�?PAUSED ──resume──�?READY
 �?                �?           �?                      �? ├──reject──�?BLOCKED ──approve─�?                      �? �?                �?                                   �? ├──────────────terminate───────────────────────────────�? └────────────────────────────────────────────────────�?TERMINAL
```

**deleteTask 限制**：只�?`NEW` �?`TERMINAL` 状态的任务可以删除；其余状态返�?`success=false`�?
补充说明�?
- `READY -> RUNNING` 由分配链路驱动，不是独立的手�?API 动作
- `RUNNING -> PAUSED` �?`TaskManager` �?`TaskStatus` 中是允许�?- `READY -> BLOCKED` �?`RUNNING -> BLOCKED` 在状态机里允许，但当前没有独立公开 API 直接触发这两个动�?
### 代码级状态约�?
以下约束直接对应 `TaskStatus.canTransitionTo()`�?
| 当前状�?| 允许进入 |
|---------|---------|
| `NEW` | `READY`, `BLOCKED`, `TERMINAL` |
| `BLOCKED` | `READY`, `TERMINAL` |
| `READY` | `RUNNING`, `PAUSED`, `BLOCKED`, `TERMINAL` |
| `RUNNING` | `BLOCKED`, `PAUSED`, `TERMINAL` |
| `PAUSED` | `READY`, `TERMINAL` |
| `TERMINAL` | �?|

---

## 4. WebSocket 消息格式

### 客户端发送（入站�?

```json
{
  "msgId": "唯一消息ID",
  "msgType": "PING",
  "subMsgType": "heartbeat",
  "project": "demoApp",
  "context": {
    "deviceId": "设备ID",
    "connRole": "task"
  }
}
```

必填字段：`msgId`、`msgType`、`project`、`context.deviceId`、`context.connRole`

### 服务端错误帧（出站）

当消息解析失败或字段缺失时，服务端返回：

```json
{
  "type": "ERROR",
  "code": "错误�?,
  "message": "错误说明"
}
```

| 错误�?| 触发条件 |
|--------|---------|
| `INVALID_FORMAT` | 消息不是合法 JSON 对象 |
| `PARSE_FAILED` | JSON 合法�?context 校验失败 |
| `MISSING_CONTEXT` | context 字段�?null |
| `MISSING_FIELDS` | deviceId/connRole/project/msgId 任一为空 |
| `CHANNEL_ERROR` | Channel 异常（exceptionCaught 触发�?|
| `INTERNAL_ERROR` | 服务端未预期异常 |

---

## 5. 健康检�?🟢 已实现（Spring Actuator�?

- **路径**：`GET /actuator/health`
- **返回**：`{"status":"UP"}`

---

## 6. 未实现接口（规划中）🔴

以下接口**尚未实现**，调用将返回 404�?

| 路径 | 规划功能 |
|------|---------|
| `POST /api/message/send` | 主动推送消息到指定设备 |
| `GET /api/metrics` | 消息速率统计（近 1min/5min�?|
| `POST /api/debug/sendRaw` | 调试用原�?Envelope 注入 |
| `GET /api/queue/drain` | 清空队列 |

> `GET /api/queue/metrics` 路径存在但只返回静�?`0` 值，不是真实统计�?

---

## 7. 返回格式说明

大多数接口使�?`ApiResponse<T>` 包装�?

```json
{
  "success": true | false,
  "message": "说明",
  "data": { ... }
}
```

部分旧接口直接返�?`Map`，格式相同但不经�?`ApiResponse` 包装类�?
