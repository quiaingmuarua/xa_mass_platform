# xa-mass-api

本模块为 API 层，负责：

- RESTful API 控制器
- DTO、AOP、全局异常处理
- 仅暴露接口，不包含业务实现

不包含启动入口，由 app 模块统一装配。

> mock、测试、接口联动等由 `xa-mass-mock` 模块统一提供。 