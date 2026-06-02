# Java External SDK Public Surface Inventory

Status: current PSDK-0 inventory for
[`JAVA_EXTERNAL_SDK_PUBLIC_READINESS_ROADMAP.md`](./JAVA_EXTERNAL_SDK_PUBLIC_READINESS_ROADMAP.md).

This inventory classifies the current public source surface under
`sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client`.

## Decisions

- Supported target is JVM Java 21. Android/device host support is a separate
  follow-up decision and must not add Android dependencies to this artifact.
- Stable caller entry points are `MassPlatform.builder()`, `tasks()`,
  `workers()`, and `workerSessions()`.
- Raw HTTP access is an advanced unstable escape hatch. It is useful for SDK
  diagnostics and temporary route coverage, but it is not the primary public
  API and carries no compatibility promise.
- Current public topology vocabulary mirrors the worker HTTP API:
  `AdapterNode*`, `NodeGroup*`, and binding DTOs are stable wire-level DTOs
  for now, but they are compatibility-risk vocabulary for broad external
  users. A later ergonomic worker-host facade may sit above them.
- `WorkerStateProjection` is compatibility-risk vocabulary because
  `projection` is platform-internal historical language. Prefer
  `WorkerStateSnapshot` or `WorkerStatusView` before a non-SNAPSHOT external
  release if the server route can support that rename.
- Current polling still accepts `WorkerDispatchHandler` as a convenience.
  Transport-neutral handler context, result, registry, invocation, and result
  sink types live under `com.xa.mass.client.worker.handler`.

## Package Classification

| Package | Public Types | Classification | Notes |
| --- | --- | --- | --- |
| `com.xa.mass.client` | `MassPlatform`, `UnstableApi` | stable entry plus marker annotation | `MassPlatform.http()` is unstable escape hatch, not primary API |
| `com.xa.mass.client.payload` | `MassPayload`, `MassPayloadException` | stable external API | handler payload view and errors |
| `com.xa.mass.client.task` | task client, request, result, view, and enum types | stable typed API | maps to documented `/api/v1/tasks` routes |
| `com.xa.mass.client.worker` | worker topology/client DTOs and command/result DTOs | mixed stable wire API and compatibility-risk vocabulary | `AdapterNode*`, `NodeGroup*`, `WorkerStateProjection` need vocabulary review before publication |
| `com.xa.mass.client.worker.handler` | dispatch context, handler result, handler registry, runtime, invocation, and result sink | stable-proposed worker handler API | transport-neutral; polling uses it now, WebSocket should reuse it later |
| `com.xa.mass.client.worker.session` | polling session, legacy dispatch handler, listener, startup/failure types | stable current polling API | polling adapts into the handler package runtime |
| `com.xa.mass.client.http` | `MassHttpClient`, `MassHttpStreamResponse` | advanced unstable escape hatch | no compatibility promise; prefer typed clients |
| `com.xa.mass.client.http.exception` | HTTP/client exception types | stable external API | messages must preserve safe request identity without secrets |

## Type Classification

| Type | Classification | Target |
| --- | --- | --- |
| `MassPlatform` | stable entry point | keep public |
| `UnstableApi` | stable marker annotation | keep public for unstable escape hatches |
| `MassPayload` | stable external API | keep public |
| `MassPayloadException` | stable external API | keep public |
| `MassHttpClient` | advanced unstable escape hatch | keep annotated; do not present as primary API |
| `MassHttpStreamResponse` | advanced unstable escape hatch | keep annotated while raw archive streams use it |
| `MassClientException` | stable exception base | keep public |
| `MassHttpException` | stable exception | keep public |
| `MassApiException` | stable exception | keep public |
| `MassProtocolException` | stable exception | keep public |
| `MassTimeoutException` | stable exception | keep public |
| `TaskClient` and task DTOs | stable typed API | keep public |
| `WorkerClient` | stable typed API | keep public |
| `AdapterNodeSpec` / `AdapterNodeRegistrationResult` | compatibility-risk wire DTOs | keep for route parity; consider ergonomic facade |
| `NodeGroupBindingSpec` / `NodeGroupBindingResult` | compatibility-risk wire DTOs | keep for route parity; consider ergonomic facade |
| `WorkerGroupSpec` / `WorkerGroupDeclarationResult` / `WorkerEventBindingSpec` | stable topology DTOs | keep public |
| `WorkerSpec` / `WorkerRegistrationResult` | stable worker DTOs with topology vocabulary | keep public; review `adapterNodeId` wording before publication |
| `WorkerPresenceResult`, polling DTOs, result submit DTOs | stable worker DTOs | keep public |
| `WorkerCommand*` DTOs | stable worker command DTOs | keep public |
| `WorkerCapabilityReport*` and `WorkerStateReport*` | stable report DTOs | keep public |
| `WorkerStateProjection` | compatibility-risk vocabulary | rename or facade before non-SNAPSHOT publication if possible |
| `DispatchContext` / `WorkerResult` | stable-proposed transport-neutral handler value types | keep public in `worker.handler` |
| `WorkerEventHandler` / `WorkerEventHandlers` | stable-proposed transport-neutral handler API | keep public |
| `WorkerEventHandlerRuntime` / `WorkerEventInvocation` | stable-proposed transport-neutral invocation API | keep public; implementation has no transport dependency |
| `WorkerResultSink` | stable-proposed transport-neutral result reporting hook | keep public; queue abstraction can layer above later |
| `WorkerSessions` / `PollingWorkerSession` | stable current session API | keep public |
| `WorkerDispatchHandler` | stable polling-compatible convenience API | keep now; new handlers can use `WorkerEventHandlers` |
| `WorkerSession*` listener/failure/startup types | stable lifecycle API | keep public |

## Public Type Index

Nested `Builder` types inherit the classification of their enclosing request
or spec type. `MassHttpClient.AuthHeader` inherits the raw HTTP unstable
classification.

| Package | Public Types | Classification |
| --- | --- | --- |
| `com.xa.mass.client` | `MassPlatform`, `UnstableApi` | stable entry / unstable marker |
| `com.xa.mass.client.payload` | `MassPayload`, `MassPayloadException` | stable payload API |
| `com.xa.mass.client.http` | `MassHttpClient`, `MassHttpClient.AuthHeader`, `MassHttpStreamResponse` | advanced unstable escape hatch |
| `com.xa.mass.client.http.exception` | `MassClientException`, `MassHttpException`, `MassApiException`, `MassProtocolException`, `MassTimeoutException` | stable exception API |
| `com.xa.mass.client.task` | `TaskClient`, `TaskAppendResult`, `TaskCommand`, `TaskCommandRequest`, `TaskCommandResult`, `TaskContract`, `TaskCounters`, `TaskCreateRequest`, `TaskCreateResult`, `TaskExecutionSpec`, `TaskExecutionView`, `TaskGetResult`, `TaskItemBatch`, `TaskItemSyncRequest`, `TaskListRequest`, `TaskListResult`, `TaskResultArchive`, `TaskResultItem`, `TaskResultReadRequest`, `TaskResultWindow`, `TaskSyncAppendResult`, `TaskTimestamps`, `TaskUpdateRequest`, `TaskUpdateResult`, `TaskView` | stable typed task API |
| `com.xa.mass.client.worker` | `WorkerClient`, `AdapterNodeSpec`, `AdapterNodeRegistrationResult`, `NodeGroupBindingSpec`, `NodeGroupBindingResult`, `WorkerCapabilityReport`, `WorkerCapabilityReportResult`, `WorkerCommand`, `WorkerCommandAck`, `WorkerCommandAckResult`, `WorkerCommandPollRequest`, `WorkerCommandPollResult`, `WorkerDispatchItem`, `WorkerEventBindingSpec`, `WorkerGroupSpec`, `WorkerGroupDeclarationResult`, `WorkerPollRequest`, `WorkerPollResult`, `WorkerPresenceResult`, `WorkerRegistrationResult`, `WorkerResultSubmitRequest`, `WorkerResultSubmitOutcome`, `WorkerSpec`, `WorkerStateProjection`, `WorkerStateReport`, `WorkerStateReportResult` | stable typed worker API with noted topology/projection vocabulary risks |
| `com.xa.mass.client.worker.handler` | `DispatchContext`, `WorkerResult`, `WorkerEventHandler`, `WorkerEventHandlers`, `WorkerEventHandlerRuntime`, `WorkerEventInvocation`, `WorkerResultSink` | stable-proposed transport-neutral handler API |
| `com.xa.mass.client.worker.session` | `WorkerSessions`, `PollingWorkerSession`, `WorkerDispatchHandler`, `WorkerSessionListener`, `WorkerSessionDispatchFailure`, `WorkerSessionPollFailure`, `WorkerSessionStartupException`, `WorkerSessionStartupFailure`, `WorkerSessionStartupStep` | stable polling session and lifecycle API |

## Route Mapping

- `TaskClient` maps to `/api/v1/tasks/**`.
- `WorkerClient` maps to `/worker-api/v1/**`.
- `PollingWorkerSession` composes public worker topology, presence, polling,
  result submit, capability report, and state report calls.
- `PollingWorkerSession` adapts worker poll items into the
  `WorkerEventHandlerRuntime` and submits handler output through a
  `WorkerResultSink`.
- Raw HTTP methods are not route contract owners; they are unstable escape
  hatches for diagnostics and temporary route coverage.

## Follow-Up Requirements

- PSDK-1 must guard against forbidden platform dependencies and imports.
- PSDK-2 must document raw HTTP as unstable and keep typed clients as the
  normal integration path.
- PSDK-3 has a minimum handler/runtime contract. Future realtime sessions must
  reuse it rather than adding transport-local handler execution.
- PSDK-5 must provide a consumer POM that does not require the platform parent.
