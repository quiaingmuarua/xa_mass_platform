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
  `xa-mass-java-sdk`.
- In-process JVM embedding callers should start from `xa-mass-embedded-sdk`.
- Public HTTP wire DTOs belong in `xa-mass-public-contract` only when the owning
  Controller method and route role are documented by
  `xa-mass-public-contract`.
- Java package names remain intentionally unchanged. `com.xa.mass.client.*`
  belongs to the external Java SDK; `com.xa.mass.sdk.*` belongs to the embedded
  SDK family.

## Guardrails

- Do not make `xa-mass-java-sdk` depend on engine, server, base, transport
  implementations, worker-pack, or embedded SDK modules.
- Do not put control-plane internals, review materialization models,
  diagnostics, bootstrap fixtures, transport frames, or embedded runtime
  assembly types into `xa-mass-public-contract`.
- Do not move worker capability code into SDK modules. Capability packs belong
  under [`../integrations`](../integrations/README.md).

## Verification

Use the module-specific README when you need a narrower command. For a directory
ownership smoke after SDK layout changes:

```bash
./mvnw -pl sdk/xa-mass-public-contract,sdk/xa-mass-java-sdk,sdk/xa-mass-embedded-sdk-api,sdk/xa-mass-embedded-sdk -am test
```

Current public-contract ownership and first-slice DTOs are summarized in
[`xa-mass-public-contract/README.md`](./xa-mass-public-contract/README.md).
