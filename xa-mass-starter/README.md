# xa-mass-starter

本模块为平台启动与聚合模块，负责组装和启动所有子系统（如 gateway、engine、api 等）。

## 架构设计

### 核心组件
- **入口类**：`MassApplication.java` - 负责组件生命周期管理
- **构建器**：`builder/MassApplicationBuilder.java` - 负责配置聚合和参数验证
- **配置类**：`GatewayConfig.java`、`EngineConfig.java` - 包含业务逻辑的配置对象

### 架构层次
```
MassApplicationBuilder (配置聚合)
    ↓
MassApplication (生命周期管理)  
    ↓
MassGateway/MassEngine (直接实例化)
```

## 主要职责

### MassApplicationBuilder
- 提供流式API进行配置聚合
- 支持多种预设配置（开发、生产、测试、API模式）
- 参数验证和默认值设置
- 构建MassApplication实例

### MassApplication  
- 统一装配各业务模块
- 管理应用启动、关闭流程
- 组件生命周期管理
- 作为 Spring Boot 启动入口

## 使用方式

### 快速启动（推荐）
```java
import com.xa.mass.starter.builder.MassApplicationBuilder;

// 开发环境
MassApplication app = MassApplicationBuilder.createDevelopment(8080, inputQueue, outputQueue);
app.start();

// 生产环境  
MassApplication app = MassApplicationBuilder.createProduction(8080, inputQueue, outputQueue);
app.start();

// API模式
MassApplication app = MassApplicationBuilder.createApiMode(8080, inputApiUrl, outputApiUrl, apiKey);
app.start();
```

### 自定义配置
```java
import com.xa.mass.starter.builder.MassApplicationBuilder;

MassApplication app = MassApplicationBuilder.create()
    .server(8080, "/ws")
    .gateway(gw -> gw
        .enabled(true)
        .maxConnections(1000)
        .inputQueue(inputQueue)
        .outputQueue(outputQueue))
    .engine(eng -> eng
        .enabled(true)
        .workerThreads(8)
        .mockData("mock/mock_config.json"))
    .build();
app.start();
```

## 依赖模块
- xa-mass-gateway
- xa-mass-engine  
- xa-mass-api

## 设计原则

> 仅在此模块中进行系统启动和聚合，其他模块不包含启动逻辑。
> mock、测试、演示入口已迁移至 `xa-mass-mock` 模块。

所有子系统通过事件总线（eventbus）解耦，事件注册与发布见项目总 README。

## 示例代码

详细使用示例请参考 `MassApplicationExample.java`，包含：
- 开发/生产/测试环境配置
- API模式配置  
- 自定义配置示例
- Mock模式集成示例 