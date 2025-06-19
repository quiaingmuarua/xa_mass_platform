# 🚀 Mass Platform

**Mass Platform** 是一个高可扩展、模块化、支持多业务接入的服务端通信与任务调度框架。
支持 WebSocket/HTTP 双接入、双队列通信、中间件链路处理、handler 分发机制，适配多 App、多业务、多角色场景。

---

## 🗂️ 目录结构

```plaintext
mass-gateway/         # 网络接入与消息处理核心
├── api/              # HTTP 控制接口
├── client/           # WebSocket 客户端
├── config/           # 配置与构建器（如 MassServerBuilder）
├── dispatcher/       # 分发与 handler 注册体系
├── middleware/       # 中间件插件机制
├── model/            # 通用数据模型（消息、设备、任务等）
├── queue/            # 队列抽象与实现（内存/Redis）
├── server/           # WebSocket 服务端核心
└── session/          # 会话管理

mass-mock/            # 演示/测试模块，集成常用逻辑组件
mass-engine/          # 任务调度与执行（规划中）
mass-api/             # 通用 HTTP 接口模块（可选）
mass-trace/           # 链路追踪与监控（可选）
doc/                  # 项目文档
```

---

## 🧩 核心模块说明

| 模块名         | 说明                                                         |
|----------------|--------------------------------------------------------------|
| mass-gateway   | 网络接入、消息分发与处理核心，支持插件化 handler/middleware   |
| mass-mock      | 演示/测试用，模拟客户端、服务端、任务流等                     |
| mass-engine    | 任务调度、规则引擎、任务流转（规划中）                        |
| mass-api       | HTTP 控制接口，支持主动推送、任务创建、状态查询等              |
| mass-trace     | 链路追踪与监控，集成 SigNoz/OpenTelemetry（可选）             |
| doc            | 设计文档、消息协议、开发规划等                                 |

---

## ⚙️ 设计原则

- **双接入**：WebSocket/HTTP 统一接入模型
- **双通道**：inputQueue/outputQueue 队列独立支持中间件
- **可组合**：dispatcher/handler/middleware 灵活注册或替换
- **灵活启动**：支持 Spring Boot 自动启动或 DSL 配置启动
- **默认实现丰富**：内置常用 handler、队列（内存/Redis）
- **多 App 支持**：Envelope 支持 appName 字段，handler/middleware 可按 app 注册
- **可追踪**：traceId 贯穿消息流，便于链路追踪与监控

---

## 🚀 快速启动

### 1. 构建所有模块
```bash
./mvnw clean install
```

### 2. 启动服务端（WebSocket Server）
```bash
cd mass-mock
mvn spring-boot:run -Dspring-boot.run.profiles=server -Dstart-class=com.xa.mass.mock.runner.WebSocketServerSpringBootApp
```

### 3. 启动模拟客户端（可多实例）
```bash
cd mass-mock
mvn spring-boot:run -Dspring-boot.run.profiles=client -Dstart-class=com.xa.mass.mock.runner.WebSocketClientSpringBootApp
```

- 支持通过配置文件（如 application.yml）灵活调整端口、客户端数量、连接 URI 等参数。

---

## 🧰 主要特性

- **WebSocket/HTTP 双接入，统一消息模型**
- **input/output 双队列架构，支持独立中间件链**
- **插件化 handler/middleware 注册与分发**
- **多 App/多业务/多角色适配**
- **任务三态建模与调度（mass-engine 规划中）**
- **链路追踪与监控（可选集成）**

---

## 📝 消息模型示例

```json
{
  "msgId": "T123456",
  "msgType": "task",
  "appName": "demoApp",
  "from": "server",
  "context": {
    "connRole": "task",
    "taskId": "BizTask_ABC",
    "traceId": "trace-xxx"
  },
  "payload": {
    "steps": [
      { "stepId": "step-1", "action": "createGroup" },
      { "stepId": "step-2", "action": "sendMessage", "dependsOn": ["step-1"] }
    ]
  }
}
```

---

## 📦 依赖环境

- JDK 8+
- Spring Boot 2.7.x
- Redis（可选，支持分布式队列/状态）
- 可选：Netty、Gson、Prometheus、SigNoz/OpenTelemetry

---

## 📌 开发里程碑

1. **网络层核心开发**（已完成）
2. **接口拓展与 DSL 构建**（已完成）
3. **任务系统模块开发 mass-engine**（进行中/下一步）
4. **可视化与监控 mass-trace**（后续）

---

## 📚 文档与支持

- 详细设计、消息协议、开发规划见 `doc/` 目录
- 如需贡献、定制或集成，请参考各模块 README 或联系维护者

---

本项目致力于打造一个可运行、可追踪、可扩展的任务调度与通信平台，支撑未来多业务、多平台、大规模任务集成等复杂场景。

---

如需更详细的接口文档、二次开发指引或业务集成示例，请查阅 `doc/` 或联系作者。