# 🚀 XA Mass Platform

**XA Mass Platform** 是一个为多设备控制、任务分发与消息编排而设计的高性能任务驱动平台。架构支持任务三态建模（初始态 ➝ 中间态 ➝ 终结态）、平台接码聚合、规则引擎抽象、Mock 驱动测试，适用于群控、异步调度、高并发接入等场景。

---

## 🧱 项目结构概览

```
xa_mass_platform/
├── doc                # 项目文档
├── xa_mass_core       # 核心模块：服务端、引擎、模型、协议等
│   └── src/main/java/com/xa/mass/core
│       ├── client     # 客户端协议与实现
│       ├── config     # 配置相关
│       ├── engine     # 任务调度与状态流转
│       ├── model      # 任务、消息、设备等数据结构
│       ├── processor  # 消息/任务处理器
│       ├── queue      # 队列与消息中间件适配
│       ├── server     # 服务端协议与实现
│       └── session    # 会话与连接管理
├── xa_mass_mock       # 模拟客户端与测试工具
│   └── src/main/java/com/xa/mass/mock
│       ├── config     # mock 配置
│       └── runner     # 独立挂载/启动脚本（server/client profile 隔离）
```

---

## 🧩 模块职责说明

| 模块名            | 说明                                              |
|-----------------|-------------------------------------------------|
| **xa_mass_core** | 核心功能模块，整合服务端、调度引擎、数据模型、协议等 |
| **xa_mass_mock** | 模拟设备客户端、任务执行器、独立挂载 runner、测试工具 |
| **doc**         | 项目设计、消息协议、开发规划等文档                  |

---

## 🚀 快速启动

### 构建所有模块
```bash
./mvnw clean install
```

### 启动核心服务端（WebSocket Server）
```bash
cd xa_mass_mock
mvn spring-boot:run -Dspring-boot.run.profiles=server -Dstart-class=com.xa.mass.mock.runner.WebSocketServerSpringBootApp
```

### 启动模拟客户端（WebSocket Client，可多实例）
```bash
cd xa_mass_mock
mvn spring-boot:run -Dspring-boot.run.profiles=client -Dstart-class=com.xa.mass.mock.runner.WebSocketClientSpringBootApp
```

- `runner/` 目录下的 Spring Boot 启动类用于独立挂载 server/client，支持 profile 隔离，便于本地和集成测试。
- 可通过配置文件（如 application.yml）灵活调整端口、客户端数量、连接 URI 等参数。

---

## 🧪 Mock 测试驱动开发

平台内置模拟设备模拟器，可模拟：
- 接收任务（msgType: send/step）
- 控制延迟 / 成功 / 超时 / 失败模式
- 回传回调（on_success / on_failed）
- 流程链路回放（trace id 支持）

示例：
```json
{
    "msgId": "12345",
    "msgType": "send",
    "steps": [
        { "stepId": "s1", "action": "createGroup" },
        { "stepId": "s2", "action": "sendMessage", "dependsOn": ["s1"] }
    ]
}
```

---

## ⚙️ 常用指令

| 操作         | 命令                                                                 |
|--------------|----------------------------------------------------------------------|
| 构建项目     | `./mvnw clean install`                                               |
| 启动服务端   | `cd xa_mass_mock && mvn spring-boot:run -Dspring-boot.run.profiles=server -Dstart-class=com.xa.mass.mock.runner.WebSocketServerSpringBootApp` |
| 启动客户端   | `cd xa_mass_mock && mvn spring-boot:run -Dspring-boot.run.profiles=client -Dstart-class=com.xa.mass.mock.runner.WebSocketClientSpringBootApp` |
| 清理 Redis   | 可使用脚本或集成工具清除任务队列及状态 hash                           |

---

## 📦 外部依赖

- JDK 8+
- Spring Boot 2.7.x
- Redis（用于任务状态缓存、队列缓存、Mock 设备跟踪）
- 可选依赖：
  - Netty（通信层优化）
  - Gson（序列化）
  - Prometheus + Grafana（可观测性监控）

---

## 📝 消息模型设计

### 设计思路
- 标准化消息格式（双向统一）
- 引入消息路由中心（双向 Dispatcher）
- Server 可订阅 Client 状态（push 模式）
- 支持多通道通信、设备连接角色，可能一个client有多个role角色连接，通过connRole进行配置

### 任务消息格式
```json
{
    "msgId": "T123456",
    "msgType": "task",
    "subMsgType": "group_send",
    "from": "client"|"server",
    "context": {
        "connRole": "messaegs_task",
        "taskId": "BizTask_ABC",
        "retryCount": 0,
        "responseLevel": "step"|"all"
    },
    "payload": {
        "steps": [
            {
                "stepId": "step-1",
                "action": "createGroup",
                "params": {...}
            },
            {
                "stepId": "step-2",
                "action": "sendMessage",
                "dependsOn": ["step-1"],
                "params": {...}
            }
        ]
    },
    "result": {
        "code": 200,
        "message": "Sent success"
    }
}
```

### 消息类型说明

| msgType  | 方向              | 用途           | 示例 subMsgType          |
|----------|-----------------|--------------|------------------------|
| task     | server ➝ client | 任务下发         | create_group, send_message |
| response | client ➝ server | 任务结果回传       | step, all（阶段、整体）        |
| ack      | 双向             | 任务或回调的确认     | task_ack, result_ack    |
| ping     | client ➝ server | 心跳           | 无                      |
| pong     | server ➝ client | 心跳响应         | 无                      |
| status   | client ➝ server | 主动上报状态（非任务类） | device_env, login_state |
| control  | server ➝ client | 服务端控制命令      | reboot, clean_env       |
| log      | client ➝ server | 上报日志、异常      | exception, stdout, metrics |
| event    | client ➝ server | 客户端主动触发行为事件  | user_click, app_crash   |
| config   | server ➝ client | 配置下发         | set_proxy, update_version |

### 设备注册消息

```json
{
    "msgType": "register",
    "context": {
        "deviceId": "A123456789",
        "connRole": "task",
        "lastAckMsgId": "T123456",
        "curStepId": "step-2"
    }
}
```

---

本项目致力于打造一个可运行、可追踪、可迭代的任务调度平台核心，支撑未来群控、多平台接码、大规模任务集成等复杂场景。