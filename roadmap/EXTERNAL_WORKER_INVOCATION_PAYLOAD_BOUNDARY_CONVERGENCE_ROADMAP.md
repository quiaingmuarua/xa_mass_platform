# External Worker Invocation Payload Boundary Convergence Roadmap

Status: proposed direction document.

## Summary

This cross-module roadmap splits worker invocation payload convergence out of
`JAVA_SDK_WORKER_RUNTIME_CAPABILITY_MODEL_CONVERGENCE_ROADMAP.md`.

Changing `DispatchContext` is not a small worker-session cleanup. It affects
handler APIs, Java SDK worker wire DTOs, server external-worker HTTP wire,
result correlation, polling and WebSocket sessions, scenario workers,
worker-pack helpers, tests, and public SDK/server docs. Those concerns need
their own executable roadmap.

Target principle:

```text
Worker handler sees worker invocation payload.
Java SDK runtime owns result correlation.
Transport owns DeliveryCommand and delivery mechanics.
Server owns external worker API wire.
Engine owns task lifecycle and result convergence.
```

The handler-facing model must not carry task lifecycle records, transport
commands, endpoint metadata, or raw wire DTOs.

## Current Code Observations

Current code facts verified from `sdk/xa-mass-java-sdk`, `xa-mass-server`,
`integrations`, and `transport`:

- `DispatchContext` is handler-facing today, but carries:
  `taskId`, `messageId`, `eventCode`, `workerId`, `input`,
  `sharedConfig`, and `rawItem`.
- `DispatchContext.rawItem` exposes `WorkerDispatchItem` to handler-adjacent
  code.
- `WorkerDispatchItem` carries task-shaped and worker-wire fields:
  `taskId`, `messageId`, `eventCode`, `taskName`, `project`, `userId`,
  `retryCount`, `workerId`, `batchId`, `input`, and `sharedConfig`.
- `WorkerResultSubmitRequest` currently requires `taskId` and `messageId`.
- `PollingWorkerSession` builds result submission from
  `DispatchContext.taskId()` and `DispatchContext.messageId()`.
- `WebSocketWorkerSession` encodes outbound result frames from
  `DispatchContext.taskId()` and `DispatchContext.messageId()`.
- `WorkerDispatchProcessor` converts `WorkerDispatchItem` directly to
  `DispatchContext`, so the wire DTO is also the source of handler context.
- `WorkerEventHandlerRuntime` is already protocol-neutral and can remain the
  handler invocation owner after its input contract is narrowed.
- Transport `DeliveryCommand` is already the assigned-delivery transport intent:
  `commandId`, `deliveryBucketId`, `selectedWorkerId`, opaque `payload`,
  `correlationRef`, and delivery observation timestamps.
- Transport-side queue records such as `QueuedPulledDispatch` /
  `PulledDeliveryMessage` already separate delivery payload and correlation
  from worker selection. Java SDK worker handlers should not understand
  `DeliveryCommand`.
- A source scan across Java SDK worker code, Java SDK tests, scenario launcher,
  worker-pack, and server worker API tests currently shows broad references to
  `DispatchContext`, `WorkerDispatchItem`, `WorkerResultSubmitRequest`,
  session failure records, and task/result fields. This is not a one-file DTO
  cleanup.
- Server `ExternalWorkerApiController` currently exposes the polling wire as
  "task dispatch items" and submit-result as `taskId/messageId` based. That
  may remain a wire-boundary fact for an early slice, but it must be explicitly
  classified instead of silently leaking into handler context.

Representative current files:

- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/handler/DispatchContext.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerDispatchItem.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerResultSubmitRequest.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session/WorkerDispatchProcessor.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session/PollingWorkerSession.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session/WebSocketWorkerSession.java`
- `transport/transport_api/src/main/java/com/xa/mass/transport/model/DeliveryCommand.java`

## Module Scope

This roadmap is not a Java SDK-only refactor.

In scope:

- `sdk/xa-mass-java-sdk`
- `xa-mass-server` external worker API controller and focused tests
- `integrations/xa-mass-scenario-launcher`
- `integrations/xa-mass-worker-pack`
- `sdk/README.md`
- `sdk/xa-mass-java-sdk/README.md`
- `doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md`

Context-only, not direct implementation owners for this roadmap:

- `transport/transport_api` and `transport/transport_runtime`
- `xa-mass-engine`
- `xa-mass-worker-runtime`

Transport and engine contracts provide boundary constraints, but this roadmap
does not change transport delivery handoff or engine result convergence unless
a later slice explicitly creates a public worker API successor.

## Proactive Convergence Surface

Do not limit this roadmap to fields explicitly named in a review comment. The
convergence must classify and either narrow, contain, or defer each surface
below.

| Surface | Current Symbols | Current Problem | Target | Workload |
| --- | --- | --- | --- | --- |
| Handler-facing invocation | `DispatchContext`, `WorkerEventHandler`, `WorkerDispatchHandler` | Handler API carries task ids, worker id, and raw wire item | Payload-first invocation only | M |
| Handler invocation result | `WorkerEventInvocation` | Stores old dispatch context as the invocation handle | Return invocation diagnostics plus result without task-shaped handler context | S/M |
| Runtime dispatch processing | `WorkerDispatchProcessor.ProcessedDispatch` | Same object is used for handler input and result correlation | Split handler invocation from runtime correlation | M |
| Runtime result submission | `WorkerResultSink`, `WorkerResultSubmitRequest`, polling submit path | Result submit currently reads `taskId/messageId` from handler context | Runtime-owned correlation feeds wire submit | M/L |
| WebSocket result queue | `WebSocketWorkerSession.OutboundResult`, result frame encoder | Queued result keeps handler context to encode task result frame | Queue result with internal correlation plus `WorkerResult` | M |
| Session failure diagnostics | `WorkerSessionDispatchFailure`, `WorkerSessionQueuedResultFailure` | Listener diagnostics expose old dispatch context and task fields | Bounded diagnostic view, separate from handler context | M |
| Worker wire DTO | `WorkerDispatchItem`, `WorkerPollResult` | Wire DTO doubles as runtime core model | Contain to worker API/protocol edge | M |
| Server worker API wire | `ExternalWorkerApiController`, external polling tests | HTTP poll/submit currently speak task-shaped wire | Either explicitly retain as wire-only or migrate with a public API decision | L |
| Integration handlers | scenario launcher, worker-pack probe/geo handlers | In-repo examples compile against old handler type | Migrate handlers to payload-first API | M |
| Tests and guards | SDK session tests, worker-pack tests, server E2E, architecture guard | Tests currently protect old vocabulary in places | Replace with allowlist/forbidden-shape guards | M/L |

Workload key:

- `S`: one package or narrow tests.
- `M`: multiple Java SDK packages or protocol variants.
- `L`: cross-module public API/server/integration impact.

This roadmap is therefore multi-slice. WIP-0 must produce a current inventory
before any field deletion or rename. A slice that only changes
`DispatchContext` but leaves result correlation, listener diagnostics, or wire
DTO containment unchanged is incomplete.

## Owner Review

Java external SDK owns:

```text
handler-facing invocation model
handler dispatch
local result capture
runtime-internal result correlation
worker protocol session orchestration
```

Transport and adapters own:

```text
DeliveryCommand
delivery bucket and selected-worker delivery constraint
opaque delivery payload
correlationRef as delivery/result bridge data
endpoint/session/final-hop mechanics
```

Server owns:

```text
external worker HTTP API wire shape
request/response validation
mapping between public worker API DTOs and SDK/runtime submit/poll calls
focused controller and E2E proof
```

Engine owns:

```text
task id and message id lifecycle meaning
attempt and retry policy
result convergence and compensation
```

Event handlers own:

```text
eventCode-specific business execution
input payload interpretation
sharedConfig interpretation
WorkerResult production
```

Task ids, message ids, attempts, batches, retries, and transport command ids
are not handler truth. They may remain in runtime-internal correlation until the
server external worker API is narrowed, but they must not remain
handler-facing context.

## Boundary Decision

Create a worker invocation boundary and move task/result correlation behind the
runtime.

Target handler-facing shape:

```text
WorkerInvocation
  eventCode
  input
  sharedConfig
```

If handler code needs the current worker identity, it should come from the
worker runtime definition/session context, not from a dispatch item. Worker
identity is not result correlation.

Target runtime-internal shape:

```text
WorkerInvocationCorrelation
  task/message/correlation fields required by current submit-result wire
  delivery/correlationRef fields required by future opaque transport result
  no business payload
  not exposed to WorkerEventHandler
```

The final names can change. The owner split cannot.

## Model Budget

This roadmap is explicitly not permission to create one DTO per layer.

Allowed model families:

| Model Family | Owner | Purpose |
| --- | --- | --- |
| `WorkerInvocation` or equivalent | Java SDK handler boundary | event code plus business payload |
| runtime correlation record | Java SDK runtime internal | submit result without exposing task facts to handlers |
| `WorkerDispatchItem` | current worker API wire boundary | transitional external wire DTO until server external worker API narrows |
| `WorkerResult` | handler output | business result |
| `WorkerResultSubmitRequest` or successor | worker API wire boundary | result publication |

Forbidden model drift:

- Do not introduce `RuntimeDispatchEnvelope`, `ProtocolDispatchCommand`,
  `WorkerSessionDispatch`, or similar same-process pass-through wrappers.
- Do not expose `DeliveryCommand` to Java external worker SDK callers,
  sessions, or handlers.
- Do not keep `taskId`, `messageId`, `taskName`, `project`, `userId`,
  attempt, batch, retry, `rawItem`, route, adapter, endpoint, connection,
  session, or delivery command fields in handler-facing context.
- Do not JSON serialize/deserialize between Java SDK runtime layers to create
  fake separation.
- Do not duplicate task-shaped result correlation across handler context,
  processed dispatch records, queued result records, and wire frames.

## Non-Goals

- Do not change engine scheduling, assignment, retry, result convergence, or
  compensation.
- Do not change transport delivery handoff or adapter final-hop execution.
- Do not move Java SDK worker runtime code into `transport`.
- Do not remove task client or task shell public models.
- Do not solve the full worker runtime capability model here. That remains in
  `JAVA_SDK_WORKER_RUNTIME_CAPABILITY_MODEL_CONVERGENCE_ROADMAP.md`.
- Do not require cross-language worker protocol redesign in this roadmap.
- Do not preserve the old `DispatchContext` as a compatibility alias after
  in-repo callers move.

## Do Not Start With

Do not start by renaming `DispatchContext` while leaving the same fields.

Do not start by passing `DeliveryCommand` into Java SDK worker sessions. That
would move transport internals in the wrong direction.

Do not start by wrapping `WorkerDispatchItem` in another session-level DTO that
still exposes the same task-shaped fields to handlers.

Do not start WIP-1 before WIP-0 proves the full caller surface. The current
blast radius includes SDK session internals, public worker API wire DTOs,
server worker API tests, scenario launcher, worker-pack handlers, and failure
listener diagnostics.

Start with inventory and result-correlation ownership. The difficult part is
not the handler parameter name; it is proving result submission still works
after task correlation is no longer handler-facing.

## WIP-0 Inventory And Blast Radius

Goal: inventory every current use of task-shaped worker dispatch fields.

Current checkpoint:

```text
EXTERNAL_WORKER_INVOCATION_PAYLOAD_BOUNDARY_CONVERGENCE_INVENTORY.md
```

Scope:

- `DispatchContext`
- `WorkerDispatchItem`
- `WorkerPollResult`
- `WorkerClient.poll(...)`
- `WorkerClient.submitResult(...)`
- `WorkerEventHandler`
- `WorkerEventHandlerRuntime`
- `WorkerEventInvocation`
- `WorkerResultSink`
- `WorkerDispatchHandler`
- `WorkerDispatchProcessor`
- `PollingWorkerSession`
- `WebSocketWorkerSession`
- `WorkerSessionDispatchFailure`
- `WorkerSessionQueuedResultFailure`
- `WorkerResultSubmitRequest`
- `ExternalWorkerApiController` worker poll and submit-result wire
- external worker polling/realtime server integration tests
- scenario launcher worker runtime
- worker-pack probe handlers
- worker-pack tests
- Java SDK worker docs and samples

Acceptance:

- Maintain the inventory/checkpoint so it classifies each `DispatchContext`
  field as: handler payload, runtime correlation, wire-only, diagnostic, or
  residue.
- Inventory includes every row from `Proactive Convergence Surface` and assigns
  each to: current slice, later slice, explicit non-goal, or separate roadmap.
- Inventory proves which callers read task fields for result submission versus
  business handler logic.
- Inventory identifies tests that assert task-shaped handler context today.
- Inventory separates Java SDK worker API wire DTOs from handler-facing models.
- Inventory explicitly decides whether server worker API `taskId/messageId`
  remains wire-only in this roadmap or moves to a successor public result
  correlation shape in a later roadmap.
- Inventory records that `DeliveryCommand` must remain outside the Java SDK
  worker runtime/handler contract.
- Inventory updates the workload estimate if the code scan reveals additional
  production or integration callers.

Verification candidates:

```bash
rg -n "DispatchContext|WorkerDispatchItem|WorkerResultSubmitRequest|rawItem|taskId\\(|messageId\\(|taskName\\(|project\\(|userId\\(|retryCount\\(|batchId\\(" sdk/xa-mass-java-sdk/src/main/java sdk/xa-mass-java-sdk/src/test/java integrations -g "*.java"
rg -n "PulledTaskDispatch|taskId|messageId|submit-result|pollTasks" xa-mass-server/src/main/java/com/xa/mass/api/internal/ExternalWorkerApiController.java xa-mass-server/src/test/java/com/xa/mass/api/internal xa-mass-server/src/test/java/com/xa/mass/server/e2e/assignment -g "*.java"
rg -n "import com\\.xa\\.mass\\.transport\\.model\\.DeliveryCommand" sdk/xa-mass-java-sdk/src/main/java sdk/xa-mass-java-sdk/src/test/java -g "*.java"
```

## WIP-1 Handler-Facing Invocation Contract

Goal: introduce or rename to a payload-first handler invocation model.

Scope:

- Add `WorkerInvocation` or narrow `DispatchContext`.
- Update `WorkerEventHandler` to receive the payload-first model.
- Update `WorkerEventHandlerRuntime`.
- Update handler-focused tests.
- Keep handler selection by `eventCode`.

Acceptance:

- Handler-facing invocation exposes only:
  `eventCode`, `input`, and `sharedConfig`.
- Handler-facing invocation does not expose `rawItem`.
- Handler-facing invocation does not expose `taskId`, `messageId`, `taskName`,
  `project`, `userId`, attempt, batch, retry, `DeliveryCommand`, route,
  adapter, endpoint, connection, or session fields.
- `WorkerEventHandlerRuntime` can invoke a handler using only the narrowed
  invocation.
- Tests prove handlers can read input/sharedConfig and produce `WorkerResult`
  without task-shaped context.

Verification candidates:

```bash
./mvnw -q -pl sdk/xa-mass-java-sdk -am test -Dtest=WorkerEventHandlerRuntimeTest,WorkerDispatchProcessorTest,JavaExternalSdkArchitectureGuardTest -Dsurefire.failIfNoSpecifiedTests=false
rg -n "taskId|messageId|taskName|project|userId|attempt|batchId|retryCount|rawItem|DeliveryCommand" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/handler -g "*.java"
```

## WIP-2 Runtime-Internal Result Correlation

Goal: move result correlation out of handler-facing context without breaking
result submission.

Scope:

- Add a runtime-internal correlation record if needed.
- Update `WorkerDispatchProcessor` to return invocation plus internal
  correlation.
- Update polling result submission.
- Update WebSocket result frame generation.
- Update failure listener records so diagnostics do not require handler-facing
  task fields.

Acceptance:

- `PollingWorkerSession` can submit result without reading task ids from
  handler context.
- `WebSocketWorkerSession` can encode result frames without reading task ids
  from handler context.
- `WorkerSessionDispatchFailure` and `WorkerSessionQueuedResultFailure` expose
  either bounded diagnostics or internal runtime records, not the old
  handler-facing task context as the only source of truth.
- Runtime correlation is not visible to `WorkerEventHandler`.
- No duplicate correlation copies are added across more than one runtime-owned
  record and one wire request/frame.

Verification candidates:

```bash
./mvnw -q -pl sdk/xa-mass-java-sdk -am test -Dtest=PollingWorkerSessionTest,WebSocketWorkerSessionTest,WorkerDispatchProcessorTest -Dsurefire.failIfNoSpecifiedTests=false
rg -n "dispatch\\.taskId\\(|dispatch\\.messageId\\(" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session -g "*.java"
```

## WIP-3 Worker Wire DTO Containment

Goal: keep worker API wire DTOs at the protocol boundary and stop treating them
as runtime core models.

Scope:

- Contain `WorkerDispatchItem` to worker API/protocol decode boundaries.
- Convert wire DTOs once into handler invocation plus runtime correlation.
- Keep `WorkerPollResult` as wire-facing if needed.
- Do not add a second Java SDK dispatch wrapper between wire and handler unless
  it owns real correlation or public handler semantics.

Acceptance:

- `WorkerDispatchItem` is not imported by handler package production code.
- `WorkerDispatchItem` is not stored in handler-facing invocation.
- Session code converts wire dispatch into invocation/correlation at the edge.
- Tests prove polling and WebSocket dispatch decode paths use the same
  invocation semantics.
- `WorkerPollResult` remains wire/API-facing or is replaced by a clearly named
  public worker API response; it is not the handler model.

Verification candidates:

```bash
./mvnw -q -pl sdk/xa-mass-java-sdk -am test -Dtest=WorkerDispatchProcessorTest,PollingWorkerSessionTest,WebSocketWorkerSessionTest,WorkerClientTest -Dsurefire.failIfNoSpecifiedTests=false
rg -n "WorkerDispatchItem" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/handler -g "*.java"
```

## WIP-4 Public SDK And Integration Migration

Goal: migrate in-repo worker handlers and samples to the payload-first handler
model.

Scope:

- scenario launcher worker runtime
- worker-pack probe handlers
- Java SDK README and examples
- worker session tests
- external worker polling/realtime integration tests

Acceptance:

- Scenario worker handlers do not read task ids from handler context for
  business behavior.
- Worker-pack probe helpers use input/sharedConfig instead of task-shaped
  handler fields.
- Public examples present event handler input as business payload, not task
  dispatch metadata.
- Result submission still works for polling and WebSocket sessions.

Verification candidates:

```bash
./mvnw -q -pl integrations/xa-mass-scenario-launcher,integrations/xa-mass-worker-pack -am -DskipTests compile
./mvnw -q -pl xa-mass-server -am test -Dtest=JavaExternalSdkPollingSessionIntegrationTest,ExternalWorkerRealtimeRegistrationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
rg -n "DispatchContext|taskId\\(|messageId\\(|rawItem" integrations/xa-mass-scenario-launcher/src/main/java integrations/xa-mass-worker-pack/src/main/java -g "*.java"
```

## WIP-5 Guards, Docs, And Residue

Goal: prevent task-shaped or transport-shaped handler invocation from returning.

Scope:

- Add or update Java SDK architecture guard.
- Update `sdk/README.md`.
- Update `sdk/xa-mass-java-sdk/README.md`.
- Update `doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md` if public SDK worker handler
  semantics change.
- Repoint the worker runtime capability roadmap to this roadmap for invocation
  payload details.

Acceptance:

- Guard fails if handler-facing package exposes `rawItem`.
- Guard fails if handler-facing package exposes task lifecycle fields.
- Guard fails if Java external SDK worker runtime imports transport
  `DeliveryCommand`.
- Guard distinguishes legal worker API wire DTO fields from illegal
  handler-facing fields.
- Active roadmaps do not duplicate this convergence as a small JWR slice.

Verification candidates:

```bash
./mvnw -q -pl sdk/xa-mass-java-sdk -am test -Dtest=JavaExternalSdkArchitectureGuardTest,WorkerEventHandlerRuntimeTest,WorkerDispatchProcessorTest,PollingWorkerSessionTest,WebSocketWorkerSessionTest -Dsurefire.failIfNoSpecifiedTests=false
rg -n "taskId|messageId|taskName|project|userId|attempt|batchId|retryCount|rawItem|DeliveryCommand" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/handler sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session -g "*.java"
rg -n "import com\\.xa\\.mass\\.transport\\.model\\.DeliveryCommand" sdk/xa-mass-java-sdk/src/main/java -g "*.java"
```

## Suggested Implementation Order

1. WIP-0 inventory and blast-radius checkpoint.
2. WIP-1 handler-facing invocation contract.
3. WIP-2 runtime-internal result correlation.
4. WIP-3 worker wire DTO containment.
5. WIP-4 public SDK and integration migration.
6. WIP-5 guards, docs, and residue.

WIP-1 and WIP-2 should usually land together or in tightly sequenced commits:
removing task fields from handler context before result correlation moves will
break result submission.

WIP-0 is not optional. If WIP-0 discovers additional production surfaces, the
roadmap must be updated before WIP-1 implementation starts.

## Roadmap Completion Criteria

This roadmap can be marked complete only when:

- Every surface listed in `Proactive Convergence Surface` is either converged,
  explicitly retained as a wire-only/public API fact, or moved to a named
  follow-up roadmap with owner and proof.
- Handler-facing invocation is payload-first and exposes only event code,
  input, and shared config.
- Java SDK worker handlers do not see task lifecycle fields, raw wire DTOs, or
  transport delivery commands.
- Runtime-internal correlation is sufficient for polling and WebSocket result
  submission.
- `WorkerDispatchItem` is contained to worker API/protocol wire boundaries or
  replaced by an explicit wire-owned successor.
- In-repo worker integrations compile and run against the final handler shape.
- Guards prevent `DispatchContext`-style task-shaped handler context from
  returning.

## Open Decisions

- Final name: `WorkerInvocation` versus narrowed `DispatchContext`.
- Whether handler-facing invocation should expose worker identity through a
  separate runtime context accessor.
- Whether `WorkerResultSubmitRequest` remains public worker API wire DTO or is
  narrowed behind a session-owned result submit API in a later roadmap.
- Whether server external worker API continues to expose `taskId/messageId` as
  wire-only result correlation or moves to a successor correlation token.
- Whether failure listener records should expose invocation diagnostics,
  runtime correlation diagnostics, or both as separate typed views.
