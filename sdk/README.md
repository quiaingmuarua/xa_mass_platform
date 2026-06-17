# SDK Directory

Status: current SDK module map.

This directory owns SDK product modules and shared public contracts. It is not
an integration/sample directory.

Global boundary guard:
[`../doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md`](../doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md).

## Module Map

| Module | Artifact | Role |
| --- | --- | --- |
| [`xa-mass-public-contract`](./xa-mass-public-contract/README.md) | `xa-mass-public-contract` | narrow public HTTP Controller wire DTOs/constants shared by server and external SDKs |
| [`xa-mass-java-sdk`](./xa-mass-java-sdk/README.md) | `xa-mass-java-sdk` | external Java client/session/handler SDK for task producers and external worker processes |
| [`xa-mass-embedded-sdk-api`](./xa-mass-embedded-sdk-api/README.md) | `xa-mass-embedded-sdk-api` | embedded SDK-facing auth, catalog, event, and model contracts |
| [`xa-mass-embedded-sdk`](./xa-mass-embedded-sdk/README.md) | `xa-mass-embedded-sdk` | in-process JVM runtime composition and starter APIs |

## Boundaries

- External callers that talk to a running server should start from
  `xa-mass-java-sdk` and its
  [`EXTERNAL_SDK_QUICKSTART.md`](./xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md).
- External task reads exposed through `xa-mass-java-sdk` may be source-labeled
  composites. `TaskView.fieldSources` is the current boundary hint for callers
  that need to distinguish shell, runtime/current, execution, timestamp, and
  compatibility fields.
- Real external worker/task proof is expected to use public SDK calls against a
  running server. `integrations/xa-mass-scenario-launcher` keeps task-producer
  and worker-process launchers separate. Server-owned dev bootstrap endpoints
  are fixtures, not SDK prerequisites.
- `integrations/xa-mass-scenario-launcher` owns human task-launcher config
  ergonomics. This is integration wiring around `xa-mass-java-sdk`, not a new
  SDK DTO or server bootstrap path.
- Operator/admin environment setup belongs to `tools/xa-mass-admin-cli` and
  server operator/API-key routes. `xa-mass-java-sdk` consumes API keys; it must
  not grow username/password operator login or API-key lifecycle helpers.
- In-process JVM embedding callers should start from `xa-mass-embedded-sdk`.
- `xa-mass-embedded-sdk` may expose advanced embedded Java transport assembly
  seams such as local adapter bootstraps or runtime factories. Those are
  in-process JVM extension points only; they are not external worker APIs,
  server worker APIs, or future cross-language adapter contracts.
- Public HTTP wire DTOs belong in `xa-mass-public-contract` only when the owning
  Controller method and route role are documented by
  `xa-mass-public-contract`.
- Java package names remain intentionally unchanged. `com.xa.mass.client.*`
  belongs to the external Java SDK; `com.xa.mass.sdk.*` belongs to the embedded
  SDK family.

## Guardrails

- Do not make `xa-mass-java-sdk` depend on engine, server, base, transport
  implementations, worker-pack, or embedded SDK modules.
- `xa-mass-java-sdk` may depend on `transport/transport_api` for stable
  transport-neutral contracts; it must not depend on `transport_runtime` or
  concrete adapter modules.
- Do not put control-plane internals, review materialization models,
  diagnostics, bootstrap fixtures, transport frames, or embedded runtime
  assembly types into `xa-mass-public-contract`.
- Do not move worker capability code into SDK modules. Capability packs belong
  under [`../integrations`](../integrations/README.md).
- Do not add operator login, session-cookie, CSRF, API-key application,
  approval, rotation, or lifecycle-management APIs to `xa-mass-java-sdk`.
  Those are server/operator or integration-helper responsibilities.
- Any SDK, public-contract, or integrations boundary change must update this
  README and [`../integrations/README.md`](../integrations/README.md) together.
  Update `xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md` when external caller
  behavior changes, and update
  [`../doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md`](../doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md)
  when dependency or ownership rules change.

## Verification

Use the module-specific README when you need a narrower command. For a directory
ownership smoke after SDK layout changes:

```bash
./mvnw -pl sdk/xa-mass-public-contract,sdk/xa-mass-java-sdk,sdk/xa-mass-embedded-sdk-api,sdk/xa-mass-embedded-sdk -am test
```

Current public-contract ownership and first-slice DTOs are summarized in
[`xa-mass-public-contract/README.md`](./xa-mass-public-contract/README.md).
