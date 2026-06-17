# External Worker Invocation Payload Boundary Inventory

Status: current code inventory for
`EXTERNAL_WORKER_INVOCATION_PAYLOAD_BOUNDARY_CONVERGENCE_ROADMAP.md`.

This inventory records the current cross-module blast radius before changing
`DispatchContext`, `WorkerDispatchItem`, external worker API wire shape, or
result correlation. It is not a completion proof.

## Scan Baseline

Current source scan covered:

- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker`
- `sdk/xa-mass-java-sdk/src/test/java/com/xa/mass/client/worker`
- `integrations/xa-mass-scenario-launcher/src/main/java`
- `integrations/xa-mass-worker-pack/src/main/java`
- `xa-mass-server/src/main/java/com/xa/mass/api/internal/ExternalWorkerApiController.java`
- external worker API focused server tests

The scan found broad references to `DispatchContext`, `WorkerDispatchItem`,
`WorkerResultSubmitRequest`, session failure records, and task/result fields.
This is a cross-package worker invocation boundary, not a one-record cleanup.

## Symbols

| Symbol | Current Owner | Current Role | Classification | Target | Workload |
| --- | --- | --- | --- | --- | --- |
| `DispatchContext` | Java SDK handler package | Handler-facing context plus task/result correlation plus raw item | Mixed owner | Replace or narrow to payload-first invocation | M |
| `WorkerDispatchItem` | Java SDK worker API package | Poll/WebSocket dispatch wire DTO and runtime source object | Wire DTO leaking into runtime | Contain at worker API/protocol edge | M |
| `WorkerPollResult` | Java SDK worker API package | Poll wire response containing `WorkerDispatchItem` | Wire DTO | Keep wire-only or replace with explicit wire response | M |
| `WorkerResultSubmitRequest` | Java SDK worker API package | Result submit wire request requiring `taskId/messageId` | Wire correlation | Keep wire-only until public API successor exists | M |
| `WorkerEventHandler` | Java SDK handler package | Public handler callback | Handler boundary | Accept payload-first invocation | M |
| `WorkerDispatchHandler` | Java SDK session package | Builder callback alias around handler shape | Duplicate handler surface | Converge with handler callback or remove alias | S/M |
| `WorkerEventHandlerRuntime` | Java SDK handler package | Protocol-neutral handler invocation | Correct owner, wrong input shape | Invoke narrowed invocation | M |
| `WorkerEventInvocation` | Java SDK handler package | Handler result plus old dispatch context | Mixed diagnostics/correlation | Return invocation diagnostics plus result | S/M |
| `WorkerResultSink` | Java SDK handler package | Custom result submit hook using old context | Mixed handler/correlation | Accept internal correlation or explicit result context | M |
| `WorkerDispatchProcessor` | Java SDK session package | Converts wire item to handler context and processed dispatch | Runtime boundary | Split invocation from runtime correlation | M |
| `PollingWorkerSession` | Java SDK session package | Polls wire items and submits result through `taskId/messageId` | Protocol session plus correlation consumer | Use processor output correlation, not handler context | M |
| `WebSocketWorkerSession` | Java SDK session package | Decodes frames, queues results, encodes result frame from context | Protocol session plus queued correlation | Queue result with internal correlation | M/L |
| `WorkerSessionDispatchFailure` | Java SDK session package | Failure diagnostic carrying `DispatchContext` | Diagnostic leaking old model | Bounded diagnostic or correlation diagnostic | M |
| `WorkerSessionQueuedResultFailure` | Java SDK session package | Queued result diagnostic carrying `DispatchContext` | Diagnostic leaking old model | Bounded diagnostic or correlation diagnostic | M |
| `ExternalWorkerApiController` poll | server worker API | Returns task-shaped polling wire items | Public worker wire | Explicitly retain wire-only or move to successor API | L |
| `ExternalWorkerApiController` submit result | server worker API | Requires `taskId/messageId` | Public result correlation wire | Explicitly retain wire-only or move to correlation token | L |
| scenario launcher handlers | integration | Use `DispatchContext` payload and listener task diagnostics | Adopter | Migrate to payload-first handler and new diagnostics | M |
| worker-pack handlers/tests | integration | Use `DispatchContext` helpers and create `WorkerDispatchItem` fixtures | Adopter/test fixture | Migrate fixtures to invocation test helpers | M |

## Field Classification

| Field | Current Carrier | Current Use | Classification | Target |
| --- | --- | --- | --- | --- |
| `eventCode` | `DispatchContext`, `WorkerDispatchItem` | Handler selection | Handler payload boundary | Keep handler-facing |
| `input` | `DispatchContext`, `WorkerDispatchItem` | Business payload | Handler payload boundary | Keep handler-facing |
| `sharedConfig` | `DispatchContext`, `WorkerDispatchItem` | Business shared config | Handler payload boundary | Keep handler-facing |
| `taskId` | `DispatchContext`, `WorkerDispatchItem`, result request | Result correlation and public wire | Runtime/wire correlation | Remove from handler-facing context |
| `messageId` | `DispatchContext`, `WorkerDispatchItem`, result request | Result correlation and public wire | Runtime/wire correlation | Remove from handler-facing context |
| `workerId` | `DispatchContext`, `WorkerDispatchItem`, poll response path | Path/session identity and diagnostics | Runtime/session context | Do not source from dispatch item for handlers |
| `taskName` | `WorkerDispatchItem` | Worker wire compatibility | Wire-only or residue | Do not expose to handlers |
| `project` | `WorkerDispatchItem` | Worker wire compatibility | Wire-only or residue | Do not expose to handlers |
| `userId` | `WorkerDispatchItem` | Worker wire compatibility | Wire-only or residue | Do not expose to handlers |
| `retryCount` | `WorkerDispatchItem` | Worker wire compatibility/diagnostic | Wire-only or residue | Do not expose to handlers |
| `batchId` | `WorkerDispatchItem` | Worker wire compatibility/correlation | Wire-only/runtime correlation | Do not expose to handlers |
| `rawItem` | `DispatchContext` | Escape hatch to full wire DTO | Boundary violation | Remove |

## Current Caller Groups

| Caller Group | Current Dependency | Risk | Target Action |
| --- | --- | --- | --- |
| Handler tests | Construct `DispatchContext` directly | Tests can preserve old public API | Replace with invocation fixture |
| Session tests | Assert `taskId/messageId` through failure records | Diagnostics may force old context to stay | Update expected diagnostics |
| `WorkerClientTest` | Poll item then submit result with task ids | Valid wire proof, not handler proof | Keep as wire proof or update after API successor |
| `ExternalWorkerApiControllerTest` | Verifies task-shaped poll/submit wire | Public API contract decision needed | Classify as wire-only or future API roadmap |
| External polling E2E | Uses `taskId/messageId` from poll item | Current public worker API behavior | Keep until server/API decision |
| Scenario launcher | Handler uses payload; listener logs task ids | Mixed adopter/diagnostic use | Migrate handler first, diagnostics second |
| Worker-pack tools | Business logic uses `input` through `DispatchContext` | Mostly easy migration, tests construct old wire item | Replace with invocation helpers |

## Decisions Needed

- Decide final handler-facing name: `WorkerInvocation` or narrowed
  `DispatchContext`.
- Decide whether `WorkerDispatchHandler` remains as a public alias or is merged
  into `WorkerEventHandler`.
- Decide whether `WorkerResultSink` remains public. If retained, it must not
  receive handler-facing invocation as result correlation.
- Decide whether server external worker API keeps `taskId/messageId` as
  wire-only result correlation for this roadmap.
- Decide the diagnostic shape for handler failure and queued-result failure
  records.

## Initial Workload Assessment

| Slice | Estimated Size | Reason |
| --- | --- | --- |
| WIP-0 inventory hardening | M | Many callers, but read-only classification |
| WIP-1 handler invocation contract | M | Public handler API and tests change |
| WIP-2 runtime correlation | M/L | Polling and WebSocket result paths must stay correct |
| WIP-3 wire DTO containment | M | Worker API DTOs must stop leaking into handler package |
| WIP-4 integrations/server proof | L | Scenario launcher, worker-pack, server worker API tests, E2E |
| WIP-5 guards/docs | M | Shape guard plus docs and residue scans |

Recommended first implementation slice:

```text
WIP-1 + WIP-2 together inside sdk/xa-mass-java-sdk,
with WorkerClient/server wire shape explicitly retained as wire-only.
```

Do not start by changing server worker API response shape. That is larger than
the handler/runtime boundary and can be a later public API convergence after
the SDK handler model is clean.
