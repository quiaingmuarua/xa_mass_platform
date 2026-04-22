# xa-mass-api

## Role

- REST controller and backend-hosted control console layer
- DTO / request-response boundary
- loaded by `xa-mass-dev-app` Spring Boot scanning

## Current Status

- not an independently verified runnable app
- API lifecycle endpoints are aligned to `TaskManager`
- the backend-hosted console shell is a validation surface, not the source of truth

## Start Here

- `src/main/java/com/xa/mass/api/internal/TaskApiController.java`
- `src/main/java/com/xa/mass/api/internal/FrontendConsoleController.java`
- `src/main/java/com/xa/mass/api/internal/WorkerDebugController.java`

## Boundaries

- do not treat this module as the Boot entry
- do not debug task lifecycle rules here first; check `xa-mass-engine` and `TaskStatus`
- use these documents before trusting module-local assumptions:
  - [`../AGENTS.md`](../AGENTS.md)
  - [`../doc/AGENT_BASELINE.md`](../doc/AGENT_BASELINE.md)
  - [`../doc/VERIFIED_RUNBOOK.md`](../doc/VERIFIED_RUNBOOK.md)
  - [`../doc/INTERNAL_API_REFERENCE.md`](../doc/INTERNAL_API_REFERENCE.md)

