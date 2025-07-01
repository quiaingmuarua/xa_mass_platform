# xa_mass_platform

## 项目简介

本项目为分层多模块架构的消息调度与分发平台，采用 Spring Boot + 多模块 Maven 管理，聚焦于高可扩展、解耦的业务与协议适配。

## 新增能力（2024年6月）

- 支持全链路 mock 测试与演示，mock 逻辑已迁移至 `xa-mass-mock` 模块。
- 支持通过统一的 `mock_config.json`（位于 `xa-mass-mock/src/main/resources/`）灵活配置 mock 设备（支持多 token）、任务等，支持模板、批量、占位符、表达式。
- mock 流程支持外部 JSON 文件热加载，便于多场景切换和复现。
- mock 结果支持分配全链路日志、规则链评估、冲突检测、分配统计等观测能力。
- 适用于端到端集成测试、规则链调试、批量分配演练、设备 token 轮询等复杂场景。

## 事件驱动架构（2024年7月更新）

本项目已全面采用事件驱动架构，所有核心模块（engine、gateway、mock等）通过统一的事件总线（eventbus）进行解耦通信。

- 事件总线基础设施位于 `xa-mass-base`，核心接口为 `EventBusFacade`，通过 `EventBusFactory.get("guava")` 获取实例。
- 所有事件均实现 `MassEvent` 接口，常见事件如：
  - 任务相关：`TaskCreatedEvent`、`TaskAuditedEvent`、`TaskAssignedEvent`（`eventbus.task` 包）
  - 设备相关：`DeviceOnlineBatchEvent`、`DeviceOfflineSingleEvent`（`eventbus.device` 包）
- 事件注册与发布示例：

```java
EventBusFacade eventBus = EventBusFactory.get("guava");
eventBus.register(TaskCreatedEvent.class, event -> {
    // 处理任务创建
});
eventBus.post(new TaskCreatedEvent(task, traceId, requestId));
```

- 事件驱动带来高内聚、低耦合、易扩展、易插拔混沌注入/监控等优势。

详细设计见 `doc/规划.md`。

## 模块结构

- **xa-mass-starter**：启动与聚合模块，唯一入口，负责组装和启动所有子系统。
- **xa-mass-gateway**：消息网关与协议适配，负责 WebSocket 连接、消息分发、中间件链等。
- **xa-mass-engine**：业务核心，负责任务调度、设备管理、分配策略等。
- **xa-mass-api**：API 层，提供 RESTful 控制器、DTO、AOP、全局异常处理等。
- **xa-mass-mock**：测试与自测模块，集成测试、端到端 mock、演示与联调。

## 分层原则

- 各业务模块独立分层，避免早期混用和"胖 common"问题。
- 入口类（如 Gateway、Engine、MassApplicationConfig）全部归于 app 模块。
- 基础模块不包含启动逻辑，由 app 统一装配和启动。

## 启动方式

1. 进入 `xa-mass-starter` 目录。
2. 运行 `src/main/java/com/xa/mass/starter/MassApplication.java`（Spring Boot 入口）。
3. 所有子系统（gateway/engine/api）均由 starter 层自动组装启动。

## 适用场景

- 高并发消息调度与分发
- 多协议适配与中间件链
- 任务调度、设备管理、业务核心解耦
- 快速集成测试与 mock

## 目录说明

- 每个模块均有独立 README 说明其职责与边界。
- 详细开发文档见各模块 `README.md`。

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

---

> 本项目持续演进中，欢迎贡献与建议！
