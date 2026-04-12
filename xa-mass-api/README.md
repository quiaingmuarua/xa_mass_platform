# xa-mass-api

## Agent Notes

- Current role: controller/template layer
- Current boot path: loaded by `xa-mass-mock` Spring Boot scanning
- Do not treat this module as an independent runnable app
- For verified commands and runtime behavior, read:
  - [`../AGENTS.md`](../AGENTS.md)
  - [`../doc/AGENT_BASELINE.md`](../doc/AGENT_BASELINE.md)
  - [`../doc/VERIFIED_RUNBOOK.md`](../doc/VERIFIED_RUNBOOK.md)

## Start Here

Open these first if you are debugging API behavior:

- `src/main/java/com/xa/mass/api/internal/TaskApiController.java`
- `src/main/java/com/xa/mass/api/internal/StatusPageController.java`
- `src/main/resources/templates/tasks.html`

本模块为 API 层，负责：

- RESTful API 控制器
- DTO、AOP、全局异常处理
- 仅暴露接口，不包含业务实现

不包含启动入口，由 app 模块统一装配。

> mock、测试、接口联动等由 `xa-mass-mock` 模块统一提供。 
