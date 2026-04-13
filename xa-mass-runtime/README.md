# xa-mass-runtime

## Role

- runtime composition layer
- builds and starts `MassApplication`, `MassEngine`, and `MassGateway`
- sits below the real Boot entry
- Maven module name is `xa-mass-runtime`; Java packages remain under `com.xa.mass.starter` for compatibility

## Current Status

- important internal assembly module
- not the verified Spring Boot entrypoint
- should not be treated as the default `spring-boot:run` target
- `xa-mass-mock` is the Spring Boot shell that hosts this runtime in the verified path
- task result write-back normalization and duplicate-callback idempotency checks live here

## Start Here

- `src/main/java/com/xa/mass/starter/MassApplication.java`
- `src/main/java/com/xa/mass/starter/builder/MassApplicationBuilder.java`
- `src/main/java/com/xa/mass/starter/MassEngine.java`

## Boundaries

- API controllers are not started here directly; they are loaded by `xa-mass-mock`
- runtime event publishing uses the current `channel/eventbus/core` and `channel/eventbus/event` packages from this module
- use these documents before trusting module-local assumptions:
  - [`../AGENTS.md`](../AGENTS.md)
  - [`../doc/AGENT_BASELINE.md`](../doc/AGENT_BASELINE.md)
  - [`../doc/VERIFIED_RUNBOOK.md`](../doc/VERIFIED_RUNBOOK.md)

