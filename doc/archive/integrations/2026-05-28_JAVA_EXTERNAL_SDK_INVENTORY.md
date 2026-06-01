# Java External SDK Inventory

Status: archived JSDK-0 inventory for
[`JAVA_EXTERNAL_SDK_ROADMAP.md`](./2026-05-28_JAVA_EXTERNAL_SDK_ROADMAP.md).

This inventory is the implementation gate for JSDK-1 through JSDK-4. It records
the current route set, module placement, dependency decisions, DTO policy, and
payload API shape for the first `xa-mass-java-sdk` implementation.

## JSDK-0 Decisions

| Topic | Decision |
| --- | --- |
| Module path | `integrations/xa-mass-java-sdk` |
| Maven artifactId | `xa-mass-java-sdk` |
| Public package root | `com.xa.mass.client` |
| Java baseline | Java 21, matching the platform reactor |
| First implementation scope | HTTP task client, worker topology client, direct polling worker calls, managed polling worker session |
| Realtime scope | WebSocket worker session implemented by the realtime/adoption follow-up; socket remains outside the Java SDK |
| Publication | reactor-scoped only; no Maven Central or external registry publication in this roadmap |
| JSON mapper | Jackson internally, because server JSON is Jackson-shaped and archive streaming benefits from Jackson/core streaming support |
| Public payload API | SDK-owned `MassPayload` / `PayloadView`; no Jackson `JsonNode` or Gson `JsonElement` in the primary public handler API |
| `xa-mass-sdk-api` reuse | no production dependency for JSDK-1 through JSDK-4 by default; define client DTOs in `xa-mass-java-sdk` |
| Server DTO reuse | no production import from `xa-mass-server` DTOs |
| WorkerGroup declaration | explicit topology operation; documented as declaration/upsert, not create-only |
| Polling session startup | sequential best-effort; no rollback; failed startup does not enter heartbeat/poll loop and reports last successful step |

The "no `xa-mass-sdk-api` production dependency" decision is deliberate. The
current `com.xa.mass.sdk.model` package mixes caller-intent models, worker
topology models, and projection snapshots. A package-level allowlist would be
too coarse for a public client. The Java client can revisit reuse later if
duplication becomes material, but the first external SDK should keep its public
type surface local and explicit.

## Repository Layout

Create the new SDK under:

```text
integrations/xa-mass-java-sdk
```

Root reactor entry:

```xml
<module>integrations/xa-mass-java-sdk</module>
```

The former Java polling sample reactor module was retired by the integrations
adoption roadmap. Java executable SDK proof now lives in
`integrations/xa-mass-scenario-launcher`.

`integrations/` means external integration ownership: public clients,
official worker references, runnable sample workers, and black-box integration
proof. It does not mean every module inside is dependency-pure. Later
`integrations/xa-mass-worker-pack` may keep
embedded SDK and transport implementation dependencies for sample/realtime
paths.

Directory guardrails:

- `integrations/xa-mass-java-sdk` is dependency-pure remote client code. It
  must not depend on engine, server, embedded SDK, worker-runtime, storage SPI,
  or transport implementation modules.
- `integrations/xa-mass-worker-pack` may be mixed during migration because it
  is a reference worker pack, not the public SDK itself.
- `integrations/samples` is for runnable public-contract examples and
  black-box proof, not kernel test fixtures or server bootstrap data.
- new modules under `integrations/` must state which public server/worker
  contract they prove or consume. If they cannot state that contract, they do
  not belong in this directory.

## Route Inventory

### Task Client Routes

These routes are in scope for JSDK-2.

| Method | Path | Client method family | Request body | Response handling |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/tasks` | `mass.tasks().list(...)` | none | `ApiResponse` envelope; client `TaskListPage` |
| `POST` | `/api/v1/tasks` | `mass.tasks().create(...)` | client `TaskCreateRequest` mapped to `TaskShellCreateApiRequest` JSON | `ApiResponse` envelope; client `TaskCreateResult` |
| `GET` | `/api/v1/tasks/{taskId}` | `mass.tasks().get(taskId)` | none | `ApiResponse` envelope; client `TaskDetail` |
| `PATCH` | `/api/v1/tasks/{taskId}` | `mass.tasks().update(taskId, ...)` | client `TaskUpdateRequest` | `ApiResponse` envelope; client `TaskUpdateResult` |
| `POST` | `/api/v1/tasks/{taskId}/items` | `mass.tasks().appendItems(taskId, ...)` | client `TaskItemBatch` | `ApiResponse` envelope; client `TaskAppendResult` |
| `POST` | `/api/v1/tasks/{taskId}/items:sync` | `mass.tasks().appendItemSync(taskId, ...)` | client `TaskItemSyncRequest` | `ApiResponse` envelope; client `TaskSyncResult` |
| `POST` | `/api/v1/tasks/{taskId}/commands` | `mass.tasks().command(taskId, ...)` | client `TaskCommandRequest` | `ApiResponse` envelope; client `TaskCommandResult` |
| `GET` | `/api/v1/tasks/{taskId}/results` | `mass.tasks().results(taskId, ...)` | none | `ApiResponse` envelope; client `TaskResultWindow` |
| `GET` | `/api/v1/tasks/{taskId}/results/archive` | `mass.tasks().archive(taskId)` | none | `ApiResponse` envelope; client `TaskResultArchive` |
| `GET` | `/api/v1/tasks/{taskId}/results/archive/content` | `mass.tasks().downloadArchive(taskId)` | none | raw streaming body; client closeable stream/result handle |

Task stage evidence routes are intentionally deferred:

- `POST /api/v1/tasks/{taskId}/items/{messageId}/stages/{stageName}/evidence`
- `GET /api/v1/tasks/{taskId}/items/{messageId}/stages`
- `GET /api/v1/tasks/{taskId}/items/{messageId}/stages/{stageName}`

They are stable server routes, but they are not required for the first task
producer and polling worker mainline. Add them only after the first task and
worker flows are proven.

### Worker Topology And Polling Routes

These routes are in scope for JSDK-3 and JSDK-4.

| Method | Path | Client method family | Request body | Response handling |
| --- | --- | --- | --- | --- |
| `POST` | `/worker-api/v1/adapter-nodes` | `mass.workers().registerAdapterNode(...)` | client `AdapterNodeSpec` | `ApiResponse` envelope; client `AdapterNodeRegistrationResult` |
| `POST` | `/worker-api/v1/worker-groups` | `mass.workers().declareGroup(...)` / optional `ensureGroup(...)` alias | client `WorkerGroupSpec` | `ApiResponse` envelope; client `WorkerGroupDeclarationResult` |
| `POST` | `/worker-api/v1/node-group-bindings` | `mass.workers().bindNodeGroup(...)` | client `NodeGroupBindingSpec` | `ApiResponse` envelope; client `NodeGroupBindingResult` |
| `POST` | `/worker-api/v1/workers` | `mass.workers().registerWorker(...)` | client `WorkerSpec` | `ApiResponse` envelope; client `WorkerRegistrationResult` |
| `POST` | `/worker-api/v1/workers/{workerId}:online` | `mass.workers().online(workerId, ...)` | optional reason | `ApiResponse` envelope; client `WorkerPresenceResult` |
| `POST` | `/worker-api/v1/workers/{workerId}:heartbeat` | `mass.workers().heartbeat(workerId, ...)` | optional reason | `ApiResponse` envelope; client `WorkerPresenceResult` |
| `POST` | `/worker-api/v1/workers/{workerId}:offline` | `mass.workers().offline(workerId, ...)` | optional reason | `ApiResponse` envelope; client `WorkerPresenceResult` |
| `POST` | `/worker-api/v1/workers/{workerId}:poll` | `mass.workers().poll(workerId, ...)` | client `WorkerPollRequest` | `ApiResponse` envelope; client `WorkerPollResult` |
| `POST` | `/worker-api/v1/workers/{workerId}:submit-result` | `mass.workers().submitResult(workerId, ...)` | client `WorkerResultSubmitRequest` | `ApiResponse` envelope; client `WorkerResultSubmitOutcome` |
| `POST` | `/worker-api/v1/workers/{workerId}/commands:poll` | `mass.workers().pollCommands(workerId, ...)` | client `WorkerCommandPollRequest` | `ApiResponse` envelope; client `WorkerCommandPollResult` |
| `POST` | `/worker-api/v1/workers/{workerId}:report-capability` | `mass.workers().reportCapability(workerId, ...)` | client `WorkerCapabilityReport` | `ApiResponse` envelope; client `WorkerCapabilityReportResult` |
| `POST` | `/worker-api/v1/workers/{workerId}:report-state` | `mass.workers().reportState(workerId, ...)` | client `WorkerStateReport` | `ApiResponse` envelope; client `WorkerStateReportResult` |
| `POST` | `/worker-api/v1/workers/{workerId}/commands/{commandId}:ack` | `mass.workers().ackCommand(workerId, commandId, ...)` | client `WorkerCommandAck` | `ApiResponse` envelope; client `WorkerCommandAckResult` |

WorkerGroup declaration is not part of `PollingWorkerSession.start()`. It is a
topology/control-plane call normally executed by deployment/setup code.
`PollingWorkerSession.start()` may register the adapter node, bind node/group,
register worker, mark online, report capability, and report state.

### Catalog And Submitter Routes

These routes are read-only support scope. They are useful for validation and
operator-like checks, but not required for the first task/worker mainline.

| Method | Path | Initial decision |
| --- | --- | --- |
| `GET` | `/api/v1/submitters/me` | include in HTTP core smoke/introspection if cheap; not required for task create |
| `GET` | `/api/v1/projects` | defer until examples need catalog browsing |
| `GET` | `/api/v1/projects/{projectCode}` | defer |
| `GET` | `/api/v1/projects/{projectCode}/events` | defer |
| `GET` | `/api/v1/catalog/events` | defer |
| `GET` | `/api/v1/catalog/events/{eventCode}` | defer |
| `GET` | `/api/v1/catalog/event-capabilities` | defer |
| `GET` | `/api/v1/catalog/worker-capabilities` | defer |
| `GET` | `/api/v1/catalog/worker-group-capabilities` | defer |

The first SDK should not block on catalog client completeness. Task and worker
mainline APIs are more important than a broad read-only catalog wrapper.

### Explicitly Out Of Scope Routes

| Route family | Reason |
| --- | --- |
| `/internal/v1/**` | internal debug/test-only surface |
| `/sample-api/**` | dev/sample bootstrap host surface, not public SDK |
| `/api/v1/auth/**` | operator browser/session auth, not API-key client mainline |
| `/api/v1/runtime/**` diagnostics | operator diagnostics; not first external Java client mainline |
| `/api/v1/api-keys/**` and API-key applications | identity-access/server management; not SDK client core |
| `/api/v1/users`, `/api/v1/roles`, `/api/v1/permissions` | server identity/admin surface |
| `/doc.html`, `/v3/api-docs`, `/actuator/**` | docs/health tooling |

## Request And Response DTO Policy

### Client-Owned DTOs

JSDK-1 through JSDK-4 should define client-owned DTOs under
`com.xa.mass.client.*`. The DTOs model caller intent and public HTTP shape, not
engine or server internals.

Initial package shape:

```text
com.xa.mass.client
com.xa.mass.client.http
com.xa.mass.client.task
com.xa.mass.client.worker
com.xa.mass.client.worker.session
com.xa.mass.client.payload
```

Avoid `com.xa.mass.sdk` because that package already means embedded SDK API.

### `xa-mass-sdk-api` Reuse Classification

Production dependency on `xa-mass-sdk-api` is not planned for JSDK-1 through
JSDK-4.

| Package/type family | Classification | Reason |
| --- | --- | --- |
| `com.xa.mass.sdk.model.MassTaskShellCreateRequest` | reference only | close to task create, but external HTTP client should own a smaller request type and avoid broad sdk-api dependency |
| `com.xa.mass.sdk.model.MassTaskItemBatchAppendRequest` | reference only | same field family as public append; client DTO should own opaque payload handling |
| `com.xa.mass.sdk.model.MassTaskCommandRequest` | reference only | simple enough to duplicate as client DTO |
| `com.xa.mass.sdk.model.TaskExecutionOptions` | reference only | server request uses this shape, but client can define its own execution spec and serialize matching JSON |
| `com.xa.mass.sdk.model.Task*Snapshot` | do not reuse initially | projection snapshots are server/SDK read models and may overexpose fields to external client |
| `com.xa.mass.sdk.model.AdapterNodeRegistration` | reference only | worker topology candidate, but client DTO should keep HTTP-specific semantics local |
| `com.xa.mass.sdk.model.NodeGroupBindingRegistration` | reference only | same as above |
| `com.xa.mass.sdk.model.WorkerGroupDeclaration` | reference only | capability truth candidate, but declaration/upsert semantics should be explicit in client DTO |
| `com.xa.mass.sdk.model.WorkerEventBinding` | reference only | simple enough to define as client `WorkerEventBindingSpec` |
| `com.xa.mass.sdk.model.WorkerRegistration` | reference only | client should own worker registration spec and transport default notes |
| `com.xa.mass.sdk.model.WorkerCapabilityReportRequest` | reference only | useful shape, but client should own public request type |
| `com.xa.mass.sdk.model.WorkerStateReportRequest` | reference only | useful shape, but client should own public request type |
| `com.xa.mass.sdk.model.WorkerCommand*` | reference only | command read/ack result shapes may be mapped later; do not pull sdk-api for first worker client |
| `com.xa.mass.sdk.event.*` | reference only | event constants/definition types are not needed for first HTTP client |
| `com.xa.mass.sdk.auth.*` | blocked | server/embedding auth implementation and principal contracts |
| `com.xa.mass.sdk.authz.*` | blocked | server/embedding authorization policy contracts |
| `com.xa.mass.sdk.catalog.*` | blocked by default | contains catalog value objects mixed with factories/registries; allow named value types only in later inventory |
| top-level `com.xa.mass.sdk.*Operations` | blocked | embedded SDK owner operations, not remote client contracts |

Reused `xa-mass-sdk-api` model count for the first implementation: `0`. The
"more than eight model types reused" threshold does not trigger.

## JSON And Payload Decision

Implementation:

- use Jackson internally for JSON encode/decode and raw HTTP body streaming.
- do not depend on Spring Boot.
- construct an SDK-local `ObjectMapper` in the HTTP core.
- hide API-key and bearer values from exception messages and logs.

Public payload API:

```java
MassPayload payload = dispatch.input();

String url = payload.requiredString("url");
URI target = payload.requiredUri("url");
Map<String, Object> asMap = payload.asMap();
```

Rules:

- `MassPayload` / `PayloadView` is the primary public shape.
- `asMap()` returns an immutable copy or immutable view.
- missing required fields throw `MassPayloadException`.
- mapper-specific tree nodes are optional escape hatches only; do not expose
  Jackson/Gson types as the default handler contract.

## Polling Session Startup Contract

Startup steps for `PollingWorkerSession.start()`:

1. register adapter node, when configured.
2. bind adapter node to WorkerGroup, when configured.
3. register worker.
4. mark worker online.
5. report capability.
6. report initial worker state.
7. start heartbeat loop.
8. start poll loop.

WorkerGroup declaration is excluded from session startup. Use
`mass.workers().declareGroup(...)` or a later `ensureGroup(...)` helper from
deployment/setup code.

Failure semantics:

- startup is sequential best-effort, not atomic.
- successful server calls are not rolled back.
- if any step before heartbeat/poll loop fails, the session is not running.
- startup failure evidence includes workerId, failed step, last successful
  step, HTTP method/path where available, and the underlying exception.
- retry after a failed startup is caller-owned; repeated startup is safe only
  because topology and registration calls are documented as idempotent/upsert
  server operations.

## Architecture Guard Plan

JSDK-1 should add a guard test that production code in
`integrations/xa-mass-java-sdk` does not import:

- `com.xa.mass.engine..`
- `com.xa.mass.starter..`
- `com.xa.mass.worker.runtime..`
- `com.xa.mass.api.internal..`
- `com.xa.mass.api.model..`
- `com.xa.mass.transport.runtime..`
- transport implementation modules.
- `com.xa.mass.sdk.auth..`
- `com.xa.mass.sdk.authz..`
- `com.xa.mass.sdk.catalog..`
- top-level embedded SDK operation interfaces under `com.xa.mass.sdk`.

If a future phase adds `xa-mass-sdk-api` reuse, each reused type must be added
to this inventory by name with owner and reason.

## JSDK-1 Readiness

JSDK-0 is ready to proceed to JSDK-1 when these facts remain true:

- target module path is `integrations/xa-mass-java-sdk`.
- root reactor will add `<module>integrations/xa-mass-java-sdk</module>`.
- first implementation does not depend on `xa-mass-sdk-api`.
- Java baseline is 21.
- internal JSON mapper is Jackson.
- public payload API is SDK-owned `MassPayload` / `PayloadView`.
- task and worker route scopes are the ones listed above.
- realtime client is still deferred.
