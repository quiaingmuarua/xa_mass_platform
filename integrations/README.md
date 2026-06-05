# Integrations Directory

Status: current integrations module map.

This directory owns real external adopters, worker capability packs, and
historical/sample fixtures. SDK product modules live under
[`../sdk`](../sdk/README.md).

SDK users should start from
[`../sdk/xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md`](../sdk/xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md).
This directory proves and packages those external paths; it is not the SDK
product owner.

Global boundary guard:
[`../doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md`](../doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md).

## Module Map

| Module | Artifact | Role |
| --- | --- | --- |
| [`xa-mass-scenario-launcher`](./xa-mass-scenario-launcher/README.md) | `xa-mass-scenario-task-launcher`, `xa-mass-scenario-worker-launcher` | primary Java external SDK adopter split into task-producer and worker-process launchers against an explicitly initialized server |
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
- Any SDK, public-contract, or integrations boundary change must update this
  README and [`../sdk/README.md`](../sdk/README.md) together. Update the
  external SDK quickstart when external task/worker caller behavior changes,
  and update the global boundary guard when dependency or ownership rules
  change.

## Current Role

- Scenario launcher is the primary Java SDK adopter, split into two executable
  process roles: task producer and worker process.
- Scenario launcher runs require catalog/rules and API keys to be prepared by
  server-owned seed/import, real control-plane setup, or test fixtures; its
  WorkerGroup, AdapterNode, Worker, and task flows remain SDK-backed external
  calls.
- Scenario launcher and worker-pack should treat SDK task read models as
  source-labeled composites when `fieldSources` is present; integrations must
  not turn those labels into new kernel truth or frontend-only models.
- Worker-pack owns reusable capabilities plus separated dev/E2E harness code.
- Samples remain protocol/dev fixtures and should not grow into a second SDK
  product surface.

## Verification

Use module-specific READMEs for focused commands. For integration ownership
smoke after layout or SDK adoption changes, prefer the active Java adopter and
worker-pack modules:

```bash
./mvnw -pl integrations/xa-mass-scenario-launcher,integrations/xa-mass-worker-pack -am test
```
