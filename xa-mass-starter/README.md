# xa-mass-starter

## Role

- lifecycle and composition layer
- builds and starts `MassApplication`, `MassEngine`, and `MassGateway`
- sits below the real Boot entry

## Current Status

- important internal assembly module
- not the verified Spring Boot entrypoint
- should not be treated as the default `spring-boot:run` target

## Start Here

- `src/main/java/com/xa/mass/starter/MassApplication.java`
- `src/main/java/com/xa/mass/starter/builder/MassApplicationBuilder.java`
- `src/main/java/com/xa/mass/starter/MassEngine.java`

## Boundaries

- API controllers are not started here directly; they are loaded by `xa-mass-mock`
- runtime still uses parts of `old.eventbus` from this layer
- use these documents before trusting module-local assumptions:
  - [`../AGENTS.md`](../AGENTS.md)
  - [`../doc/AGENT_BASELINE.md`](../doc/AGENT_BASELINE.md)
  - [`../doc/VERIFIED_RUNBOOK.md`](../doc/VERIFIED_RUNBOOK.md)
