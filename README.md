# xa_mass_platform

## 项目亮点

- **事件驱动**：全模块统一事件总线，极致解耦与可观测性
- **全链路 Mock**：支持端到端集成测试、规则链调试、批量分配演练
- **分层多模块**：Starter 聚合，Engine/Gateway/API/Mock 各司其职
- **高可扩展性**：插件式中间件、规则链、设备/任务动态扩展
- **观测与调试**：分配日志、规则评估、冲突检测、热加载 mock 配置

## 架构概览

本项目采用分层多模块架构，所有核心模块（engine、gateway、mock等）通过统一的事件总线（eventbus）进行解耦通信，支持高并发消息调度与分发、复杂业务解耦与快速集成测试。

## 快速上手

1. 进入 `xa-mass-starter` 目录。
2. 运行 `src/main/java/com/xa/mass/starter/MassApplication.java`（Spring Boot 入口）。
3. 所有子系统（gateway/engine/api/mock）均由 starter 层自动组装启动。
4. 可通过 mock 配置文件体验全链路演示与集成测试。

## 主要模块

| 模块              | 说明                                                                     |
|-----------------|------------------------------------------------------------------------|
| xa-mass-starter | 启动与聚合，唯一入口，负责组装和启动所有子系统                                                |
| xa-mass-gateway | 消息网关、协议适配，负责 WebSocket 连接、消息分发、中间件链                                    |
| xa-mass-engine  | 业务核心，负责任务调度、设备管理、分配策略、规则链等                                             |
| xa-mass-api     | RESTful API 层，提供控制器、DTO、AOP、全局异常处理                                     |
| xa-mass-mock    | 测试与自测模块，集成测试、端到端 mock、演示与联调                                            |
| xa-mass-base    | 基础设施模块，包含事件总线（eventbus）、通用模型、异常、枚举，以及 **json-dsl**（通用对象生成/批量 mock 框架）等 |

## 事件驱动架构

**EventBus 2.0 - 泛型重构版本**

- 🔧 **泛型支持**：`StreamEventBusFacade<T>` 支持任意类型事件（POJO/MassEvent）
- ⚡ **高性能**：20K+ events/sec，精确匹配分发，无继承查找开销
- 🔄 **多实现**：内存(`InMemoryMessageStream`) / Redis(`LettuceRedisStream`)分布式支持
- 📊 **完整Trace**：MassEvent提供eventId、timestamp、traceId等元数据

### 使用示例

```java
// 场景1：结构化事件（生产环境推荐）
var stream = new InMemoryMessageStream<MassEvent>("events", MassEvent.class);
var eventBus = new StreamEventBusFacade<MassEvent>(stream);

class TaskListener {
    @MassSubscribe
    public void onTaskCreated(TaskCreatedEvent event) {
        log.info("任务创建: {} [Trace: {}]", event.getDescription(), event.getTraceId());
    }
}
eventBus.register(new TaskListener());
eventBus.post(new TaskCreatedEvent(task, "trace-001", "req-001"));

// 场景2：轻量级POJO（快速开发）
var eventBus = new StreamEventBusFacade<Object>(stream);
eventBus.post(new OrderCreated("order-123", 99.99));  // 任意POJO
eventBus.post("系统维护通知");                          // 基础类型

// 场景3：分布式事件总线
var redisStream = new LettuceRedisStream<MassEvent>("distributed-events", MassEvent.class);
var distributedEventBus = new StreamEventBusFacade<MassEvent>(redisStream);
```

**迁移说明**：旧版 Guava EventBus 已废弃，推荐使用 `StreamEventBusFacade` + `MessageStream`

详细文档：[EventBus README](./xa-mass-base/src/main/java/com/xa/mass/base/channel/eventbus/README.md)

## Mock 能力

- mock 配置：`xa-mass-mock/src/main/resources/mock_config.json`
- 支持多 token、批量、表达式、热加载
- mock 流程支持外部 JSON 文件热加载，便于多场景切换和复现
- mock 结果支持分配全链路日志、规则链评估、冲突检测、分配统计等观测能力
- 适用场景：端到端测试、规则链调试、设备轮询等

## 分层原则

- 各业务模块独立分层，避免早期混用和"胖 common"问题。
- 入口类（如 Gateway、Engine、MassApplicationConfig）全部归于 app 模块。
- 基础模块不包含启动逻辑，由 app 统一装配和启动。
- mock 相关请勿引入 core 依赖，保持依赖方向（core 不依赖 mock）。

## 文档与开发

- 每个模块均有独立 README 说明其职责与边界。
- 详细开发文档见 `doc/` 目录。
- [API 文档](doc/xa-mass-api-接口文档.md)

## Maven 仓库配置与离线依赖

构建默认从 Maven Central 拉取依赖，若在公司内网无法直接访问外网，可在 `~/.m2/settings.xml` 中配置镜像仓库。例如：

```xml
<settings>
  <mirrors>
    <mirror>
      <id>internal-nexus</id>
      <mirrorOf>*</mirrorOf>
      <url>http://nexus.example.com/repository/maven-public/</url>
    </mirror>
  </mirrors>
  <!-- 如有需要可在 <servers> 中配置认证信息 -->
</settings>
```

对于离线或受限环境，可先在有网络的机器上运行 `populate_dependencies.sh` 预下载依赖，
脚本会对各模块执行 `mvn dependency:go-offline`，将依赖缓存到本地仓库，随后将 `~/.m2/repository` 目录拷贝到目标环境即可。

## 贡献指南

- 欢迎 issue、PR、建议，详见各模块 README
- 代码风格与模块边界见各模块说明
- mock 相关请勿引入 core 依赖，保持依赖方向

---

> 本项目持续演进中，欢迎贡献与建议！

> 说明：
> - **json-dsl** 是 xa-mass-base 下的一个子模块（包），用于批量 mock、表达式、嵌套、内置函数等，广泛服务于 mock、测试和规则链。
