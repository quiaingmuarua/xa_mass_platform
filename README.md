# 🚀 XA Mass Platform

**XA Mass Platform** 是一个为多设备控制、任务分发与消息编排而设计的高性能任务驱动平台。架构支持任务三态建模（初始态 ➝ 中间态 ➝ 终结态）、平台接码聚合、规则引擎抽象、Mock 驱动测试，适用于群控、异步调度、高并发接入等场景。

---

## 🧱 项目结构概览

xa_mass_platform/
├── mass_server     # 主服务入口：任务接入、API层、通信协议入口（HTTP/WebSocket）
├── mass_engine     # 核心引擎：任务调度、状态管理、批处理、三态流程
├── mass_model      # 通用模型：任务结构体、状态定义、通信协议实体
├── mass_common     # 公共模块：Redis工具、配置中心、日志、通用工具
├── mass_client     # 模拟客户端：任务执行模拟器、设备回调器、Mock平台测试

---

## 🧩 模块职责说明

| 模块名 | 说明 |
|--------|------|
| **mass_server** | SpringBoot 接入层，支持 HTTP & WebSocket 接入，分发消息到调度核心 |
| **mass_engine** | 核心任务调度器：处理 List ➝ Batch ➝ Result 状态转换、任务执行控制 |
| **mass_model**  | 定义任务格式、设备状态、通信消息结构，作为系统传输数据契约 |
| **mass_common** | 提供 RedisKey 工具类、日志封装、重试策略、配置中心管理等 |
| **mass_client** | 模拟设备客户端，可模拟执行任务、回调上报、测试消息通路完整性 |

---

## 🎯 架构设计目标

- ✅ **任务驱动**：每个任务拥有清晰状态流（待执行、执行中、成功/失败）
- ✅ **中间态批处理**：支持复杂多步任务（如：建群 ➝ 群发 ➝ 上报）
- ✅ **平台聚合接码**：统一多个接码平台接入格式，配置驱动，无需代码改动
- ✅ **规则引擎驱动**：基于配置控制任务分发逻辑，抽离硬编码策略
- ✅ **Mock 驱动开发**：内置设备模拟器，支持无真实设备联调、演练全流程

---

## 🚀 快速启动

> 构建所有模块：

```bash
 ./mvnw clean install
```

启动主服务：

```bash
cd mass_server
./mvnw spring-boot:run
```

启动模拟设备客户端（Mock）：
```bash
cd mass_client
./mvnw spring-boot:run
```


🧪 Mock 测试驱动开发

项目内置模拟设备模拟器，可模拟：
	•	接收任务（msgType: send/step）
	•	控制延迟 / 成功 / 超时 / 失败模式
	•	回传回调（on_success / on_failed）
	•	流程链路回放（trace id 支持）

示例：

{
  "msgId": "12345",
  "msgType": "send",
  "steps": [
    { "stepId": "s1", "action": "createGroup" },
    { "stepId": "s2", "action": "sendMessage", "dependsOn": ["s1"] }
  ]
}


⸻

⚙️ 常用指令

操作	命令
构建项目	./mvnw clean install
启动主服务	cd mass_server && ./mvnw spring-boot:run
启动 Mock 客户端	cd mass_client && ./mvnw spring-boot:run
清理 Redis 状态	可使用脚本或集成工具清除任务队列及状态 hash


⸻



模块	作用
TaskQueueManager	核心任务调度器
BatchDispatcher	批量任务派发模块
AppHandlerStage	每种任务执行的行为封装
MockDeviceSimulator	模拟设备消息通路
PlatformManager	平台 DSL 注册与封装调度


⸻

📦 外部依赖
	•	JDK 8+
	•	Spring Boot 2.7.x
	•	Redis（用于任务状态缓存、队列缓存、Mock 设备跟踪）
	•	可选依赖：
	•	Netty（通信层优化）
	•	Gson（序列化）
	•	Prometheus + Grafana（可观测性监控）

⸻


🧭 后续目标（Roadmap）
	•	完善平台插件 DSL 扩展格式
	•	可视化任务状态流图（前端集成）
	•	支持流量录制与回放（Trace + Replay）
	•	提供规则引擎管理界面

⸻

本项目致力于打造一个可运行、可追踪、可迭代的任务调度平台核心，支撑未来群控、多平台接码、大规模任务集成等复杂场景。

⸻