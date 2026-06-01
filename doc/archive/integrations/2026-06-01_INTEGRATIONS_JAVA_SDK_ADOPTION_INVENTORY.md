# Integrations Java SDK Adoption Inventory

Status: archived IJS-0 inventory for
[INTEGRATIONS_JAVA_SDK_ADOPTION_ROADMAP.md](./2026-06-01_INTEGRATIONS_JAVA_SDK_ADOPTION_ROADMAP.md).

This document records current Java callers under `integrations/` that use the
public platform APIs, raw network transports, or Java SDK entry points. It is a
code-fact inventory, not a target-state claim.

## Classification

| Path | Current surface | Classification | Decision |
| --- | --- | --- | --- |
| `integrations/xa-mass-scenario-launcher` | `MassPlatform`, typed task and worker clients, `PollingWorkerSession`, `WebSocketWorkerSession` | strategic SDK consumer | Primary internal Java SDK adopter. Keep as the standard proof owner for task shell, item append, worker topology, polling/WebSocket dispatch, and result submit. |
| `integrations/samples/java` | removed | retired sample fixture | Removed after scenario-launcher gained polling and WebSocket SDK black-box proof. Java socket is not a public SDK session in this roadmap. |
| `integrations/xa-mass-worker-pack` | embedded dev/sample worker runtime, raw WebSocket/socket frame clients, sample command/fault behavior | worker-pack embedded/dev runtime | Do not add SDK dependency for the current sample fault harness. Future non-fault builtin worker groups should use SDK sessions/public APIs. |
| `integrations/xa-mass-scenario-launcher/.../DevBootstrapClient.java` | raw `HttpClient` to `/sample-api/bootstrap/**` | dev-only bootstrap caller | Allowed because the Java SDK does not own sample/admin catalog or rule bootstrap. It must not seed tasks or workers. |
| `integrations/xa-mass-scenario-launcher/src/test/**` | fake server assertions for `/api/v1/**` and `/worker-api/v1/**` | test-only assertion/fake server | Allowed as request-path verification for SDK-backed launcher code. |
| `integrations/xa-mass-java-sdk/src/test/**` | fake server assertions for public platform routes | SDK owner test fixture | Allowed because SDK tests own route contract verification. |

## Public Route Call Ownership

Strategic Java integration production code should not hard-code public
platform routes under `/api/v1/**` or `/worker-api/v1/**` when the Java SDK has
a typed client or session for that route.

Allowed current route-literal owners:

| Owner | Route family | Reason |
| --- | --- | --- |
| `integrations/xa-mass-java-sdk/src/main/**` | `/api/v1/**`, `/worker-api/v1/**` | SDK internals are the typed public API client implementation. |
| `integrations/xa-mass-java-sdk/src/test/**` | `/api/v1/**`, `/worker-api/v1/**` | SDK fake servers and request assertions validate the client contract. |
| `integrations/xa-mass-scenario-launcher/src/test/**` | `/api/v1/**`, `/worker-api/v1/**` | Scenario launcher tests assert that SDK-backed code hits the expected public routes. |
| `integrations/xa-mass-scenario-launcher/src/main/.../DevBootstrapClient.java` | `/sample-api/bootstrap/**` | Dev metadata bootstrap is outside the Java SDK public task/worker API surface. |

There are no approved production Java route literals for `/api/v1/**` or
`/worker-api/v1/**` outside `integrations/xa-mass-java-sdk` at this inventory
point.

## Raw Network Usage

Raw network types are not banned by themselves. The guard is route-literal
based so business HTTP and transport fixtures remain possible.

| Path | Raw network usage | Owner reason |
| --- | --- | --- |
| `integrations/xa-mass-scenario-launcher/.../ScenarioClientFactory.java` | `HttpClient` injected into `MassPlatform` | Allowed SDK client configuration, not direct route calling. |
| `integrations/xa-mass-scenario-launcher/.../DevBootstrapClient.java` | `HttpClient` | Allowed dev-only bootstrap caller for `/sample-api/bootstrap/**`. |
| `integrations/xa-mass-worker-pack/.../SampleWorkerWebSocketClient.java` | `org.java_websocket.client.WebSocketClient` | Worker-pack owns current dev/sample realtime frame clients until public SDK realtime sessions exist. |
| `integrations/xa-mass-worker-pack/.../SampleWorkerSocketClient.java` | `Socket` | Worker-pack owns current dev/sample socket frame client until the socket public SDK decision exists. |

## Java Sample Decisions

| Sample | Keep now? | Replacement proof | Retirement trigger |
| --- | --- | --- | --- |
| `worker-polling` | No | `JavaScenarioLauncherBlackBoxIntegrationTest` proves the SDK-backed polling path | Trigger fired; sample removed. |
| `worker-websocket` | No | `JavaScenarioLauncherBlackBoxIntegrationTest` proves the SDK-backed WebSocket path | Trigger fired; sample removed. |
| `worker-socket` | No | Socket is not a Java SDK session in this roadmap | Trigger fired; sample removed. |

## Worker-Pack Audit

Worker-pack currently remains non-adopting for the Java SDK.

Reasons:

- no mainline `com.xa.mass.client` imports exist in worker-pack;
- no duplicate raw Java HTTP calls to `/worker-api/v1/**` topology or
  worker-control routes were found in worker-pack production code;
- the current worker-pack value is sample/dev realtime frame clients plus
  command/fault behavior, which remains local;
- the SDK already owns a transport-neutral handler runtime and WebSocket
  session, but worker-pack can consume it only when the relevant path is a
  normal builtin worker session rather than the current sample command/fault
  harness.

Future worker-pack convergence should therefore be a targeted adoption, not a
directory-placement dependency migration.
