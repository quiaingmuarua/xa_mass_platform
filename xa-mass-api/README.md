# xa-mass-api

## Role

- REST controller and status page layer
- DTO / request-response boundary
- loaded by `xa-mass-mock` Spring Boot scanning

## Current Status

- not an independently verified runnable app
- API lifecycle endpoints are aligned to `TaskManager`
- UI pages are a secondary validation surface, not the source of truth

## Start Here

- `src/main/java/com/xa/mass/api/internal/TaskApiController.java`
- `src/main/java/com/xa/mass/api/internal/StatusPageController.java`
- `src/main/resources/templates/tasks.html`

## Boundaries

- do not treat this module as the Boot entry
- do not debug task lifecycle rules here first; check `xa-mass-engine` and `TaskStatus`
- use these documents before trusting module-local assumptions:
  - [`../AGENTS.md`](../AGENTS.md)
  - [`../doc/AGENT_BASELINE.md`](../doc/AGENT_BASELINE.md)
  - [`../doc/VERIFIED_RUNBOOK.md`](../doc/VERIFIED_RUNBOOK.md)
  - [`../doc/内部管理接口文档.md`](../doc/内部管理接口文档.md)
