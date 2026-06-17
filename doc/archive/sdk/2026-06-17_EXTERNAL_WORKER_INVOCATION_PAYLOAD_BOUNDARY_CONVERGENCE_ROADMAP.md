# External Worker Invocation Payload Boundary Convergence Roadmap

Status: complete; archived after residue scan and focused proof.

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
Worker result submit uses opaque resultCorrelationRef.
Java SDK runtime owns local result-correlation carriage.
Server owns external worker API wire migration.
Transport owns DeliveryCommand and delivery mechanics.
Server owns external worker API wire.
Engine owns task lifecycle and result convergence.
```

The handler-facing model must not carry task lifecycle records, transport
commands, endpoint metadata, or raw wire DTOs.
`taskId/messageId` are current legacy result-correlation bridge fields, not the
target worker API or handler contract.

## Implementation Snapshot

Current implemented shape:

- Java SDK handler API uses `WorkerInvocation` with `eventCode`, `input`, and
  `sharedConfig`.
- `DispatchContext` has been removed from production Java SDK worker code.
- Java SDK `WorkerDispatchItem`, embedded `PulledTaskDispatch`, server worker
  poll responses, WebSocket task frames, and socket task frames expose opaque
  `resultCorrelationRef` instead of public `taskId/messageId` result
  correlation.
- Java SDK and embedded/server worker result submit requests use
  `resultCorrelationRef`.
- `TaskDispatchDeliveryCorrelationCodec` is the named embedded/starter bridge
  that maps opaque worker correlation back to engine task/message/attempt
  identity for result convergence.
- Worker-pack, scenario runner, chaos/soak support, and server external worker
  tests have been migrated to the opaque correlation contract.
- `TransportConvergenceArchitectureGuardTest` protects the worker-facing
  surfaces from reintroducing task/attempt/transport fields.

## Before Convergence Observations

Historical code facts verified from `sdk/xa-mass-java-sdk`, `xa-mass-server`,
`sdk/xa-mass-embedded-sdk`, `integrations`, and `transport`:

- `DispatchContext` was handler-facing, but carried:
  `taskId`, `messageId`, `eventCode`, `workerId`, `input`,
  `sharedConfig`, and `rawItem`.
- `DispatchContext.rawItem` exposed `WorkerDispatchItem` to handler-adjacent
  code.
- `WorkerDispatchItem` carried task-shaped and worker-wire fields:
  `taskId`, `messageId`, `eventCode`, `taskName`, `project`, `userId`,
  `retryCount`, `workerId`, `batchId`, `input`, and `sharedConfig`.
- `WorkerResultSubmitRequest` required `taskId` and `messageId`.
- Embedded/server-facing `PulledTaskDispatch` exposed
  `taskId`, `messageId`, `attemptId`, `attemptNo`, `retryCount`, and `batchId`.
- Embedded/server-facing `WorkerResultSubmitRequest` required
  `taskId` and `messageId`, with optional attempt/lease/trace fields.
- `PollingWorkerSession` built result submission from
  `DispatchContext.taskId()` and `DispatchContext.messageId()`.
- `WebSocketWorkerSession` encoded outbound result frames from
  `DispatchContext.taskId()` and `DispatchContext.messageId()`.
- `WorkerDispatchProcessor` converted `WorkerDispatchItem` directly to
  `DispatchContext`, so the wire DTO is also the source of handler context.
- `WorkerEventHandlerRuntime` was already protocol-neutral and could remain the
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
- Server `ExternalWorkerApiController` exposed the polling wire as
  "task dispatch items" and submit-result as `taskId/messageId` based. That
  is a legacy bridge fact, not an acceptable long-term public worker API
  semantic.

Representative current files:

- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/handler/DispatchContext.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerDispatchItem.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerResultSubmitRequest.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session/WorkerDispatchProcessor.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session/PollingWorkerSession.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session/WebSocketWorkerSession.java`
- `sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/worker/PulledTaskDispatch.java`
- `sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/worker/WorkerResultSubmitRequest.java`
- `transport/transport_api/src/main/java/com/xa/mass/transport/model/DeliveryCommand.java`

## Module Scope

This roadmap is not a Java SDK-only refactor.

In scope:

- `sdk/xa-mass-java-sdk`
- `sdk/xa-mass-embedded-sdk`
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
| Runtime dispatch processing | `WorkerDispatchProcessor.ProcessedDispatch` | Same object is used for handler input and result correlation | Split into `WorkerInvocation` plus opaque `ResultCorrelationRef` | M |
| Runtime result submission | `WorkerResultSink`, Java/embedded `WorkerResultSubmitRequest`, polling submit path | Result submit currently reads `taskId/messageId` from handler context | Opaque `ResultCorrelationRef` feeds submit; task ids only in legacy bridge | M/L |
| WebSocket result queue | `WebSocketWorkerSession.OutboundResult`, result frame encoder | Queued result keeps handler context to encode task result frame | Queue result with `ResultCorrelationRef` plus `WorkerResult` | M |
| Session failure diagnostics | `WorkerSessionDispatchFailure`, `WorkerSessionQueuedResultFailure` | Listener diagnostics expose old dispatch context and task fields | Bounded diagnostic view, separate from handler context | M |
| Worker wire DTO | `WorkerDispatchItem`, `WorkerPollResult`, `PulledTaskDispatch` | Wire DTO doubles as runtime core model and exposes task correlation | Contain to edge; migrate result correlation to opaque ref | M/L |
| Server worker API wire | `ExternalWorkerApiController`, external polling tests | HTTP poll/submit currently speak task-shaped wire | Migrate to opaque `resultCorrelationRef`; task fields are legacy bridge only | L |
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
opaque result-correlation carriage
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
legacy taskId/messageId bridge removal from public worker API
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
are not handler truth and are not the target worker API correlation contract.
They may remain only inside a named legacy bridge while the public worker API is
being migrated to opaque `resultCorrelationRef`.

## Boundary Decision

Create a worker invocation boundary and move result correlation to an opaque
reference.

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

Target result-correlation shape:

```text
ResultCorrelationRef
  value
  opaque to workers, handlers, SDK callers, and external worker API clients
  resolves to task lifecycle identity only inside owner runtime/legacy bridge
```

Target runtime processing shape:

```text
WorkerDispatchExecution
  invocation: WorkerInvocation
  resultCorrelationRef: ResultCorrelationRef
  legacyBridge: optional taskId/messageId bridge until public wire migrates
```

The final names can change. The owner split cannot.

## Model Budget

This roadmap is explicitly not permission to create one DTO per layer.

Allowed model families:

| Model Family | Owner | Purpose |
| --- | --- | --- |
| `WorkerInvocation` or equivalent | Java SDK handler boundary | event code plus business payload |
| `ResultCorrelationRef` | external worker API / SDK runtime boundary | opaque result association |
| runtime correlation record | Java SDK runtime internal | carry opaque correlation without exposing task facts to handlers |
| legacy task-result bridge | server/SDK migration adapter | temporary taskId/messageId mapping with exit criteria |
| `WorkerDispatchItem` / `PulledTaskDispatch` | current worker API wire boundary | transitional external wire DTO until resultCorrelationRef lands |
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
- Do not treat `taskId/messageId` as the long-term public worker result
  correlation model.
- Do not allow naked `taskId/messageId` correlation outside a named legacy
  bridge or migration adapter with an explicit exit condition.
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
- Do not preserve `taskId/messageId` as final public worker API semantics.
  Keeping them temporarily requires a named legacy bridge and proof that worker
  handlers and runtime core use opaque correlation.
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

Do not split handler cleanup from result correlation cleanup. The first
executable slice must convert dispatch wire into both payload-first
`WorkerInvocation` and opaque `ResultCorrelationRef`; otherwise result submit
will either break or keep task fields alive as a hidden second truth.

Do not start WIP-1 before WIP-0 proves the full caller surface. The current
blast radius includes SDK session internals, embedded SDK worker DTOs, public
worker API wire DTOs, server worker API tests, scenario launcher, worker-pack
handlers, and failure listener diagnostics.

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
- `PulledTaskDispatch`
- embedded SDK `WorkerResultSubmitRequest`
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
- Inventory separates Java SDK and embedded SDK worker API wire DTOs from
  handler-facing models.
- Inventory identifies every current `taskId/messageId` worker result
  correlation site and classifies it as: handler leak, runtime leak, legacy
  bridge, or public wire migration target.
- Inventory records an exit condition for every legacy bridge site. A bridge
  without an exit condition is not accepted.
- Inventory records that `DeliveryCommand` must remain outside the Java SDK
  worker runtime/handler contract.
- Inventory updates the workload estimate if the code scan reveals additional
  production or integration callers.

Verification candidates:

```bash
rg -n "DispatchContext|WorkerDispatchItem|WorkerResultSubmitRequest|rawItem|taskId\\(|messageId\\(|taskName\\(|project\\(|userId\\(|retryCount\\(|batchId\\(" sdk/xa-mass-java-sdk/src/main/java sdk/xa-mass-java-sdk/src/test/java integrations -g "*.java"
rg -n "PulledTaskDispatch|WorkerResultSubmitRequest|taskId|messageId|attemptId|batchId" sdk/xa-mass-embedded-sdk/src/main/java sdk/xa-mass-embedded-sdk/src/test/java -g "*.java"
rg -n "PulledTaskDispatch|taskId|messageId|submit-result|pollTasks" xa-mass-server/src/main/java/com/xa/mass/api/internal/ExternalWorkerApiController.java xa-mass-server/src/test/java/com/xa/mass/api/internal xa-mass-server/src/test/java/com/xa/mass/server/e2e/assignment -g "*.java"
rg -n "import com\\.xa\\.mass\\.transport\\.model\\.DeliveryCommand" sdk/xa-mass-java-sdk/src/main/java sdk/xa-mass-java-sdk/src/test/java -g "*.java"
```

## WIP-1 Invocation And Opaque Correlation Pivot

Goal: atomically split dispatch wire into payload-first invocation plus opaque
result correlation.

Scope:

- Add `WorkerInvocation` or narrow `DispatchContext`.
- Add `ResultCorrelationRef` or equivalent opaque public/runtime value.
- Update `WorkerEventHandler` to receive the payload-first model.
- Update `WorkerEventHandlerRuntime`.
- Update `WorkerDispatchProcessor` to return invocation plus result
  correlation.
- Update polling result submission to use the correlation output, not handler
  context.
- Update WebSocket result frame generation and queued results to use the
  correlation output, not handler context.
- Introduce a named legacy bridge only where current wire still requires
  `taskId/messageId`.
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
- `PollingWorkerSession` can submit result without reading task ids from
  handler context.
- `WebSocketWorkerSession` can encode or queue result frames without reading
  task ids from handler context.
- The only allowed naked `taskId/messageId` use in Java SDK session code is a
  named legacy bridge or migration adapter.
- `ResultCorrelationRef` is opaque: handlers and public SDK callers cannot
  derive task id or message id from it through API fields.

Verification candidates:

```bash
./mvnw -q -pl sdk/xa-mass-java-sdk -am test -Dtest=WorkerEventHandlerRuntimeTest,WorkerDispatchProcessorTest,PollingWorkerSessionTest,WebSocketWorkerSessionTest,JavaExternalSdkArchitectureGuardTest -Dsurefire.failIfNoSpecifiedTests=false
rg -n "taskId|messageId|taskName|project|userId|attempt|batchId|retryCount|rawItem|DeliveryCommand" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/handler -g "*.java"
rg -n "dispatch\\.taskId\\(|dispatch\\.messageId\\(" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session -g "*.java"
```

## WIP-2 WorkerResultSink And Diagnostics Boundary

Goal: prevent public hooks and diagnostics from re-exposing old task-shaped
dispatch context.

Scope:

- Decide whether `WorkerResultSink` remains public.
- If retained, change it to receive either `ResultCorrelationRef` or an
  explicit public result context that contains only opaque correlation.
- Update `WorkerSessionDispatchFailure`.
- Update `WorkerSessionQueuedResultFailure`.
- Update listener tests and integration logging.

Acceptance:

- `WorkerResultSink` does not receive handler-facing invocation as result
  correlation.
- `WorkerResultSink` does not receive internal correlation records.
- `WorkerResultSink` does not expose `taskId/messageId`.
- `WorkerSessionDispatchFailure` and `WorkerSessionQueuedResultFailure` expose
  bounded diagnostics and/or opaque correlation, not the old handler-facing
  task context as the only source of truth.
- Scenario launcher and worker-pack logging no longer require
  `failure.dispatch().taskId()` or `messageId()`.

Verification candidates:

```bash
./mvnw -q -pl sdk/xa-mass-java-sdk,integrations/xa-mass-scenario-launcher,integrations/xa-mass-worker-pack -am test -Dtest=PollingWorkerSessionTest,WebSocketWorkerSessionTest,WorkerDispatchProcessorTest -Dsurefire.failIfNoSpecifiedTests=false
rg -n "WorkerResultSink|failure\\.dispatch\\(\\)\\.taskId|failure\\.dispatch\\(\\)\\.messageId|WorkerSessionDispatchFailure|WorkerSessionQueuedResultFailure" sdk/xa-mass-java-sdk/src/main/java integrations -g "*.java"
```

## WIP-3 Worker Wire DTO And Public API Correlation Migration

Goal: migrate external worker wire DTOs toward opaque result correlation and
contain legacy task-shaped fields.

Scope:

- Contain `WorkerDispatchItem` to worker API/protocol decode boundaries.
- Contain embedded SDK `PulledTaskDispatch` to external worker API wire.
- Add or migrate to a public `resultCorrelationRef` field for worker poll
  response and submit-result request.
- Convert wire DTOs once into handler invocation plus runtime correlation.
- Keep `WorkerPollResult` as wire-facing if needed.
- Keep `taskId/messageId` only in named legacy request/response bridge classes
  until server public API is cut over.
- Do not add a second Java SDK dispatch wrapper between wire and handler unless
  it owns real correlation or public handler semantics.

Acceptance:

- `WorkerDispatchItem` is not imported by handler package production code.
- `WorkerDispatchItem` is not stored in handler-facing invocation.
- `PulledTaskDispatch` and embedded SDK worker result submit request expose
  `resultCorrelationRef` as the target result association field.
- Any retained `taskId/messageId` fields are explicitly marked legacy bridge
  with exit criteria in docs/tests.
- Session code converts wire dispatch into invocation/correlation at the edge.
- Tests prove polling and WebSocket dispatch decode paths use the same
  invocation semantics.
- `WorkerPollResult` remains wire/API-facing or is replaced by a clearly named
  public worker API response; it is not the handler model.

Verification candidates:

```bash
./mvnw -q -pl sdk/xa-mass-java-sdk,sdk/xa-mass-embedded-sdk,xa-mass-server -am test -Dtest=WorkerDispatchProcessorTest,PollingWorkerSessionTest,WebSocketWorkerSessionTest,WorkerClientTest,PulledTaskDispatchTest,ExternalWorkerApiControllerTest -Dsurefire.failIfNoSpecifiedTests=false
rg -n "WorkerDispatchItem" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/handler -g "*.java"
rg -n "taskId|messageId" sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/worker xa-mass-server/src/main/java/com/xa/mass/api/internal/ExternalWorkerApiController.java -g "*.java"
```

## WIP-4 Public SDK And Integration Migration

Goal: migrate in-repo worker handlers and samples to the payload-first handler
model and opaque result correlation.

Scope:

- scenario launcher worker runtime
- worker-pack probe handlers
- Java SDK README and examples
- worker session tests
- external worker polling/realtime integration tests
- embedded SDK worker DTO tests
- server external worker API tests

Acceptance:

- Scenario worker handlers do not read task ids from handler context for
  business behavior.
- Worker-pack probe helpers use input/sharedConfig instead of task-shaped
  handler fields.
- Public examples present event handler input as business payload, not task
  dispatch metadata.
- Public examples submit results with opaque result correlation, not
  taskId/messageId, once the public wire successor lands.
- Result submission still works for polling and WebSocket sessions.

Verification candidates:

```bash
./mvnw -q -pl integrations/xa-mass-scenario-launcher,integrations/xa-mass-worker-pack -am -DskipTests compile
./mvnw -q -pl sdk/xa-mass-embedded-sdk,xa-mass-server -am test -Dtest=PulledTaskDispatchTest,ExternalWorkerApiControllerTest,JavaExternalSdkPollingSessionIntegrationTest,ExternalWorkerRealtimeRegistrationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
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
- Guard fails if Java SDK session code reads `DispatchContext.taskId()` or
  `DispatchContext.messageId()`.
- Guard allows naked `taskId/messageId` in worker surfaces only in named legacy
  bridge/migration packages or classes with an exit condition.
- Guard distinguishes legacy worker API wire bridge fields from illegal
  handler-facing fields.
- Active roadmaps do not duplicate this convergence as a small JWR slice.

Verification candidates:

```bash
./mvnw -q -pl sdk/xa-mass-java-sdk -am test -Dtest=JavaExternalSdkArchitectureGuardTest,WorkerEventHandlerRuntimeTest,WorkerDispatchProcessorTest,PollingWorkerSessionTest,WebSocketWorkerSessionTest -Dsurefire.failIfNoSpecifiedTests=false
rg -n "taskId|messageId|taskName|project|userId|attempt|batchId|retryCount|rawItem|DeliveryCommand" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/handler -g "*.java"
rg -n "DispatchContext\\.taskId\\(|DispatchContext\\.messageId\\(|dispatch\\.taskId\\(|dispatch\\.messageId\\(" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session -g "*.java"
rg -n "taskId|messageId" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/worker xa-mass-server/src/main/java/com/xa/mass/api/internal/ExternalWorkerApiController.java -g "*.java"
rg -n "import com\\.xa\\.mass\\.transport\\.model\\.DeliveryCommand" sdk/xa-mass-java-sdk/src/main/java -g "*.java"
```

## Suggested Implementation Order

1. WIP-0 inventory and blast-radius checkpoint.
2. WIP-1 invocation and opaque correlation pivot.
3. WIP-2 `WorkerResultSink` and diagnostics boundary.
4. WIP-3 worker wire DTO and public API correlation migration.
5. WIP-4 public SDK and integration migration.
6. WIP-5 guards, docs, and residue.

WIP-1 is the first executable code slice. It intentionally combines handler
invocation cleanup with result-correlation cleanup because separating them
creates a broken or misleading intermediate state.

WIP-0 is not optional. If WIP-0 discovers additional production surfaces, the
roadmap must be updated before WIP-1 implementation starts.

## Roadmap Completion Criteria

This roadmap can be marked complete only when:

- Every surface listed in `Proactive Convergence Surface` is either converged
  to opaque result correlation, explicitly retained as a named legacy bridge
  with exit criteria, or moved to a named follow-up roadmap with owner and
  proof.
- Handler-facing invocation is payload-first and exposes only event code,
  input, and shared config.
- Java SDK worker handlers do not see task lifecycle fields, raw wire DTOs, or
  transport delivery commands.
- Runtime result correlation is opaque and sufficient for polling and WebSocket
  result submission.
- `taskId/messageId` are not accepted as final public worker API result
  correlation semantics.
- `WorkerDispatchItem` is contained to worker API/protocol wire boundaries or
  replaced by an explicit wire-owned successor.
- `PulledTaskDispatch` and server external worker API result submit wire either
  use opaque `resultCorrelationRef` or are explicitly tracked as legacy bridge
  residue with exit conditions.
- In-repo worker integrations compile and run against the final handler shape.
- Guards prevent `DispatchContext`-style task-shaped handler context from
  returning.

## Resolved Decisions

- The handler-facing model is `WorkerInvocation`, not a narrowed
  `DispatchContext`; `DispatchContext` was removed rather than retained as a
  compatibility alias.
- Worker identity is not exposed through handler invocation. Worker identity
  remains session/path context outside the payload-first handler contract.
- The opaque result association value is named `ResultCorrelationRef` /
  `resultCorrelationRef`.
- Worker result submit requests use `resultCorrelationRef`; task-shaped
  `taskId/messageId` request fields were not preserved as the public worker
  result contract.
- Server external worker poll and submit-result wire use
  `resultCorrelationRef`.
- Failure listener records expose bounded invocation and correlation
  diagnostics without reintroducing the old task-shaped handler context.
