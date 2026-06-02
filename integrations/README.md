# Integrations Directory

Status: current integrations module map.

This directory owns real external adopters, worker capability packs, and
historical/sample fixtures. SDK product modules live under
[`../sdk`](../sdk/README.md).

Global boundary guard:
[`../doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md`](../doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md).

## Module Map

| Module | Artifact | Role |
| --- | --- | --- |
| [`xa-mass-scenario-launcher`](./xa-mass-scenario-launcher/README.md) | `xa-mass-scenario-launcher` | primary Java external SDK adopter for registering topology, starting polling workers, and submitting scenario tasks to a running server |
| [`xa-mass-worker-pack`](./xa-mass-worker-pack/README.md) | `xa-mass-worker-pack` | worker capability pack and active server E2E harness support |
| [`samples`](./samples/README.md) | none | historical/dev fixtures only; not a long-term public SDK product surface |

## Boundaries

- Integrations prove SDK value through real external registration and task
  submission. They must not become SDK owners.
- `xa-mass-scenario-launcher` should use `sdk/xa-mass-java-sdk` as the standard
  external Java entrypoint.
- `xa-mass-worker-pack` should keep real worker capability code here. Embedded
  SDK usage is acceptable only for explicitly documented E2E harness or
  dev-shell reasons, not as the public external worker path.
- Server startup must not seed production task/worker truth as a substitute for
  external registration.

## Active Direction

The current direction is to move integrations from simple demonstrations toward
business-useful worker capabilities and realistic external SDK adoption. The
worker-pack convergence record is
[`../doc/archive/integrations/2026-06-01_INTEGRATIONS_WORKER_PACK_SDK_CONVERGENCE_ROADMAP.md`](../doc/archive/integrations/2026-06-01_INTEGRATIONS_WORKER_PACK_SDK_CONVERGENCE_ROADMAP.md).

## Verification

Use module-specific READMEs for focused commands. For integration ownership
smoke after layout or SDK adoption changes, prefer the active Java adopter and
worker-pack modules:

```bash
./mvnw -pl integrations/xa-mass-scenario-launcher,integrations/xa-mass-worker-pack -am test
```
