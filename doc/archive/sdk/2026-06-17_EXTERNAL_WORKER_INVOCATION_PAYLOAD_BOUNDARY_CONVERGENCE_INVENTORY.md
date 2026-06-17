# External Worker Invocation Payload Boundary Inventory

Status: historical pre-implementation inventory for
`EXTERNAL_WORKER_INVOCATION_PAYLOAD_BOUNDARY_CONVERGENCE_ROADMAP.md`.

This inventory records the cross-module blast radius before changing
`DispatchContext`, `WorkerDispatchItem`, external worker API wire shape, or
result correlation. It is retained as provenance; current mainline proof lives
in the focused tests and `TransportConvergenceArchitectureGuardTest`.

Mainline implementation has landed:

- Java SDK handlers use `WorkerInvocation(eventCode, input, sharedConfig)`.
- Worker result submission uses opaque `resultCorrelationRef`.
- Naked `taskId/messageId` worker API correlation has been moved behind the
  embedded/starter correlation bridge.
- `DispatchContext` is no longer a production Java SDK worker type.

## Scan Baseline

Current source scan covered:

- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker`
- `sdk/xa-mass-java-sdk/src/test/java/com/xa/mass/client/worker`
- `sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/worker`
- `sdk/xa-mass-embedded-sdk/src/test/java/com/xa/mass/sdk/worker`
- `integrations/xa-mass-scenario-launcher/src/main/java`
- `integrations/xa-mass-worker-pack/src/main/java`
- `xa-mass-server/src/main/java/com/xa/mass/api/internal/ExternalWorkerApiController.java`
- external worker API focused server tests

The original scan found broad references to `DispatchContext`, `WorkerDispatchItem`,
`PulledTaskDispatch`, `WorkerResultSubmitRequest`, session failure records, and
task/result fields. This is a cross-package worker invocation boundary, not a
one-record cleanup.

## Symbols

| Symbol | Current Owner | Current Role | Classification | Target | Workload |
| --- | --- | --- | --- | --- | --- |
| `DispatchContext` | Java SDK handler package | Handler-facing context plus task/result correlation plus raw item | Mixed owner | Replace or narrow to payload-first invocation | M |
| `WorkerDispatchItem` | Java SDK worker API package | Poll/WebSocket dispatch wire DTO and runtime source object | Wire DTO leaking into runtime | Contain at worker API/protocol edge | M |
| `WorkerPollResult` | Java SDK worker API package | Poll wire response containing `WorkerDispatchItem` | Wire DTO | Carry opaque `resultCorrelationRef`; legacy task fields bridge only | M |
| Java SDK `WorkerResultSubmitRequest` | Java SDK worker API package | Result submit wire request requiring `taskId/messageId` | Legacy wire correlation | Migrate to opaque `resultCorrelationRef` | M |
| embedded `PulledTaskDispatch` | embedded SDK/server worker package | Server-facing poll DTO exposing task/attempt fields | Public worker wire | Carry opaque `resultCorrelationRef`; task fields legacy bridge only | L |
| embedded `WorkerResultSubmitRequest` | embedded SDK/server worker package | Server-facing submit command requiring `taskId/messageId` | Legacy wire correlation | Migrate to opaque `resultCorrelationRef` | L |
| `WorkerEventHandler` | Java SDK handler package | Public handler callback | Handler boundary | Accept payload-first invocation | M |
| `WorkerDispatchHandler` | Java SDK session package | Builder callback alias around handler shape | Duplicate handler surface | Converge with handler callback or remove alias | S/M |
| `WorkerEventHandlerRuntime` | Java SDK handler package | Protocol-neutral handler invocation | Correct owner, wrong input shape | Invoke narrowed invocation | M |
| `WorkerEventInvocation` | Java SDK handler package | Handler result plus old dispatch context | Mixed diagnostics/correlation | Return invocation diagnostics plus result | S/M |
| `WorkerResultSink` | Java SDK handler package | Custom result submit hook using old context | Mixed handler/correlation | Delete, internalize, or expose only opaque public correlation | M |
| `WorkerDispatchProcessor` | Java SDK session package | Converts wire item to handler context and processed dispatch | Runtime boundary | Split invocation from opaque `ResultCorrelationRef` | M |
| `PollingWorkerSession` | Java SDK session package | Polls wire items and submits result through `taskId/messageId` | Protocol session plus correlation consumer | Use opaque correlation, not handler context | M |
| `WebSocketWorkerSession` | Java SDK session package | Decodes frames, queues results, encodes result frame from context | Protocol session plus queued correlation | Queue result with opaque correlation | M/L |
| `WorkerSessionDispatchFailure` | Java SDK session package | Failure diagnostic carrying `DispatchContext` | Diagnostic leaking old model | Bounded diagnostic or correlation diagnostic | M |
| `WorkerSessionQueuedResultFailure` | Java SDK session package | Queued result diagnostic carrying `DispatchContext` | Diagnostic leaking old model | Bounded diagnostic or correlation diagnostic | M |
| `ExternalWorkerApiController` poll | server worker API | Returns task-shaped polling wire items | Public worker wire | Return opaque `resultCorrelationRef`; task fields legacy bridge only | L |
| `ExternalWorkerApiController` submit result | server worker API | Requires `taskId/messageId` | Public result correlation wire | Accept opaque `resultCorrelationRef`; task fields legacy bridge only | L |
| scenario launcher handlers | integration | Use `DispatchContext` payload and listener task diagnostics | Adopter | Migrate to payload-first handler and new diagnostics | M |
| worker-pack handlers/tests | integration | Use `DispatchContext` helpers and create `WorkerDispatchItem` fixtures | Adopter/test fixture | Migrate fixtures to invocation test helpers | M |

## Field Classification

| Field | Current Carrier | Current Use | Classification | Target |
| --- | --- | --- | --- | --- |
| `eventCode` | `DispatchContext`, `WorkerDispatchItem` | Handler selection | Handler payload boundary | Keep handler-facing |
| `input` | `DispatchContext`, `WorkerDispatchItem` | Business payload | Handler payload boundary | Keep handler-facing |
| `sharedConfig` | `DispatchContext`, `WorkerDispatchItem` | Business shared config | Handler payload boundary | Keep handler-facing |
| `resultCorrelationRef` | target public/runtime correlation | Result association without task identity | Opaque correlation | Add as target field |
| `taskId` | `DispatchContext`, `WorkerDispatchItem`, `PulledTaskDispatch`, result request | Result correlation and public wire | Legacy bridge | Remove from handler/runtime/public target; allow only migration bridge |
| `messageId` | `DispatchContext`, `WorkerDispatchItem`, `PulledTaskDispatch`, result request | Result correlation and public wire | Legacy bridge | Remove from handler/runtime/public target; allow only migration bridge |
| `workerId` | `DispatchContext`, `WorkerDispatchItem`, poll response path | Path/session identity and diagnostics | Runtime/session context | Do not source from dispatch item for handlers |
| `taskName` | `WorkerDispatchItem` | Worker wire compatibility | Wire-only or residue | Do not expose to handlers |
| `project` | `WorkerDispatchItem` | Worker wire compatibility | Wire-only or residue | Do not expose to handlers |
| `userId` | `WorkerDispatchItem` | Worker wire compatibility | Wire-only or residue | Do not expose to handlers |
| `retryCount` | `WorkerDispatchItem` | Worker wire compatibility/diagnostic | Wire-only or residue | Do not expose to handlers |
| `batchId` | `WorkerDispatchItem`, `PulledTaskDispatch` | Worker wire compatibility/correlation | Legacy bridge or diagnostic | Do not expose to handlers; not result truth |
| `rawItem` | `DispatchContext` | Escape hatch to full wire DTO | Boundary violation | Remove |

## Current Caller Groups

| Caller Group | Current Dependency | Risk | Target Action |
| --- | --- | --- | --- |
| Handler tests | Construct `DispatchContext` directly | Tests can preserve old public API | Replace with invocation fixture |
| Session tests | Assert `taskId/messageId` through failure records | Diagnostics may force old context to stay | Update expected diagnostics |
| `WorkerClientTest` | Poll item then submit result with task ids | Test can preserve legacy wire as target | Update to opaque correlation or isolate legacy bridge proof |
| `ExternalWorkerApiControllerTest` | Verifies task-shaped poll/submit wire | Public API contract currently protects old shape | Update to `resultCorrelationRef`; legacy assertions must be isolated |
| External polling E2E | Uses `taskId/messageId` from poll item | Current public worker API behavior | Migrate to `resultCorrelationRef` |
| Scenario launcher | Handler uses payload; listener logs task ids | Mixed adopter/diagnostic use | Migrate handler first, diagnostics second |
| Worker-pack tools | Business logic uses `input` through `DispatchContext` | Mostly easy migration, tests construct old wire item | Replace with invocation helpers |

## Decisions Needed

- Decide final handler-facing name: `WorkerInvocation` or narrowed
  `DispatchContext`.
- Decide whether `WorkerDispatchHandler` remains as a public alias or is merged
  into `WorkerEventHandler`.
- Decide whether `WorkerResultSink` remains public. If retained, it must not
  receive handler-facing invocation, internal correlation records, or
  taskId/messageId.
- Decide exact legacy bridge name and package for temporary taskId/messageId
  compatibility.
- Decide exact public worker API transition shape for `resultCorrelationRef`.
- Decide the diagnostic shape for handler failure and queued-result failure
  records.

## Initial Workload Assessment

| Slice | Estimated Size | Reason |
| --- | --- | --- |
| WIP-0 inventory hardening | M | Many callers, but read-only classification |
| WIP-1 invocation and opaque correlation pivot | M/L | Public handler API and result paths change together |
| WIP-2 sink and diagnostics boundary | M | Public hooks and listener diagnostics must not preserve old context |
| WIP-3 wire DTO/public API migration | L | Java SDK, embedded SDK, server API, and E2E use result correlation |
| WIP-4 integrations/server proof | L | Scenario launcher, worker-pack, server worker API tests, E2E |
| WIP-5 guards/docs | M | Shape guard plus docs and residue scans |

Recommended first implementation slice:

```text
WIP-1 inside `sdk/xa-mass-java-sdk`, with any taskId/messageId use isolated to a
named legacy bridge and session/runtime code using opaque `ResultCorrelationRef`.
```

Do not start by changing server worker API response shape unless the slice is
explicitly WIP-3. However, do not describe the current server shape as a valid
final public API; it is legacy bridge residue until WIP-3 lands.
