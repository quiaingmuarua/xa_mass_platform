# xa-mass-starter

本模块为平台启动与聚合模块，负责组装和启动所有子系统（如 gateway、engine、api 等）。

- 入口类：`MassApplication.java`
- 配置类：`MassApplicationConfig.java`
- 主要职责：
  - 统一装配各业务模块
  - 管理应用启动、关闭、配置
  - 作为 Spring Boot 启动入口

依赖：
- xa-mass-gateway
- xa-mass-engine
- xa-mass-api

> 仅在此模块中进行系统启动和聚合，其他模块不包含启动逻辑。
> mock、测试、演示入口已迁移至 `xa-mass-mock` 模块。 