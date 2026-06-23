# Java SDK Worker Runtime Capability Model Convergence Roadmap

Status: archived after mainline completion. Worker command ability remains an
explicitly deferred worker-control decision. Worker action/reply naming was
superseded and landed by
`JAVA_SDK_WORKER_ACTION_CHANNEL_MODEL_CONVERGENCE_ROADMAP.md`; historical
sections below may mention the previous `WorkerInvocation` /
`WorkerResultSubmission` vocabulary only as pre-action-channel context.

## Summary

The Java external SDK now has a much cleaner worker public model:

- `WorkerAction(actionId, replyRef, eventCode, body, sharedConfig)` is the
  single worker action item.
- `WorkerActionResult(success, code, body)` is the handler output.
- `WorkerActionReply(replyRef, success, code, body)` is the result-submit fact.
- old Java SDK worker topology DTOs, command poll/ack DTOs,
  `WorkerDispatchItem`, `ResultCorrelationRef`, `WorkerEventHandlers`,
  `WorkerEventHandlerRuntime`, and handler-package `WorkerInvocation` have been
  removed.
- worker-local evidence is explicit through
  `reportHandlerEvidence(...)` and `reportRuntimeEvidence(...)`, not hidden
  capability/state reporting during session start.

That cleanup made this roadmap narrower and more executable. The next remaining
problem is no longer public DTO bloat or hidden worker registration; it is
worker runtime orchestration: protocol classes still own protocol loops,
dispatch execution wiring, result-submit mechanics, maintenance scheduling, and
lifecycle callback taxonomy directly.

Target principle:

```text
Worker runtime owns worker abilities.
Protocol drivers own only network exchange mechanics.
Event handlers own business execution.
Server / worker-runtime own platform worker truth and evidence application.
```

Polling workers are not one-shot pull clients. WebSocket workers are not a
separate capability class. Both are background worker runtimes with different
protocol drivers.

## Current Facts

Current code facts verified after JWR-1/JWR-2/JWR-3/JWR-4/JWR-5/JWR-6 landed:

- `WorkerClient` exposes:
  `registerWorker`, `online`, `heartbeat`, `offline`, `poll`,
  `submitResult`, `reportHandlerEvidence`, and `reportRuntimeEvidence`.
- `WorkerClient` no longer exposes `registerAdapterNode`, `bindNodeGroup`,
  `reportCapability`, `reportState`, `pollCommands`, or `ackCommand`.
- `WorkerRuntimeDefinition` is the protocol-neutral Java SDK worker ability
  model: `workerId`, `workerGroupId`, attributes, and event handlers.
- `WorkerSpec.polling(definition)` and `WorkerSpec.realtime(definition)` map the
  same definition to explicit worker registration specs.
- `WorkerRuntimes.polling(definition)` and
  `WorkerRuntimes.webSocket(definition)` build protocol runtimes over the same
  definition.
- `WorkerRuntime` is the public managed worker runtime shell:
  `workerId`, `workerGroupId`, `transportHint`, `reporter`, `start`,
  `isRunning`, and `close`.
- `WorkerRuntimeReporter` is the explicit worker-local evidence/report owner.
  It publishes handler evidence from `WorkerRuntimeDefinition` and runtime
  evidence through `WorkerClient`.
- `WorkerRuntimeContext` is the package-private common runtime derivation
  owner for polling and WebSocket runtimes. It derives worker identity,
  immutable attributes/handlers, listener, executor, dispatch processor, and
  reporter from `WorkerRuntimeDefinition` plus runtime-common options.
- `WorkerRuntimeOptions` is package-private runtime-common wiring only:
  listener and executor. It does not own polling interval, endpoint,
  reconnect, queue, or protocol-specific settings.
- `WorkerSessionSpec` has been removed and is not a public compatibility alias.
- `PollingWorkerRuntime.start()` no longer registers the worker. It performs:
  `online -> heartbeat loop -> poll loop`.
- `WebSocketWorkerRuntime.start()` no longer registers the worker. It performs:
  `connect WebSocket -> result sender loop`.
- `PollingWorkerRuntime.start()` no longer publishes handler/runtime evidence.
- `WebSocketWorkerRuntime.start()` no longer publishes handler/runtime
  evidence.
- `online`, `heartbeat`, and `offline` remain legitimate session-presence APIs.
  They should not be collapsed into eligibility or evidence-report cleanup.
- `PollingWorkerRuntime.Builder` and `WebSocketWorkerRuntime.Builder` no longer
  expose public worker ability setters. They retain protocol/session options
  such as poll interval, endpoint, reconnect settings, listener, and executor.
- `WorkerDispatchProcessor` is already the common invocation processor:
  `WorkerInvocation -> WorkerEventHandler -> WorkerResult`.
- `PollingWorkerRuntime` gets `WorkerDispatchProcessor` and
  `WorkerRuntimeReporter` from `WorkerRuntimeContext`, then submits
  `WorkerResultSubmission` through the worker HTTP API.
- `WebSocketWorkerRuntime` gets `WorkerDispatchProcessor` and
  `WorkerRuntimeReporter` from `WorkerRuntimeContext`, then encodes a WebSocket
  result frame.
- `PollingWorkerProtocolDriver` owns polling worker-api exchange:
  `online`, `heartbeat`, `poll`, `submitResult`, and `offline`.
- `WebSocketWorkerProtocolDriver` owns WebSocket connect URI construction,
  connector selection, and dispatch/result frame codec.
- `PollingWorkerRuntime` uses package-private `WorkerRuntimeMaintenanceLoop`
  for heartbeat upkeep.
- WebSocket runtime still owns reconnect policy, queued result handling, result
  sender loop, and lifecycle callbacks.
- Polling runtime still owns poll loop scheduling, poll backoff, handler
  dispatch orchestration, and lifecycle callbacks.
- `WorkerRuntimeListener` is a broad diagnostic callback sink, but failure
  reporting is now one public model:
  `WorkerRuntimeFailureEvent(kind, reason, resultCorrelationRef,
  consecutiveFailures, errorType, errorMessage, context)`. Dedicated public
  failure records such as heartbeat/frame/poll/connection failure have been
  removed. `context` is diagnostic-only and must not become control-flow
  contract.
- Scenario launcher and worker-pack have been migrated to
  `WorkerRuntimeDefinition` plus explicit `WorkerSpec` registration.
- `WorkerRuntimeStartupStep.REGISTER_WORKER` has been removed; registration
  failure is no longer classified as managed runtime startup failure.
- `EXTERNAL_WORKER_CAPABILITY_EVIDENCE_API_CONVERGENCE_ROADMAP.md` is now
  superseded historical context. Current Java SDK code uses
  `reportHandlerEvidence(...)` and `reportRuntimeEvidence(...)`; future
  reporter decisions belong to this roadmap's JWR-6 slice.
- `sdk/xa-mass-java-sdk/README.md` examples use the current
  `WorkerResult.success(String)` / `WorkerResult.failure(resultCode, result)`
  shape.

Representative current files:

- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerClient.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerInvocation.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerResultSubmission.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerRuntimeDefinition.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerSpec.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/runtime/PollingWorkerRuntime.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/runtime/PollingWorkerProtocolDriver.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/runtime/WebSocketWorkerRuntime.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/runtime/WebSocketWorkerProtocolDriver.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/runtime/WebSocketConnector.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/runtime/WorkerDispatchProcessor.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/runtime/WorkerRuntime.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/runtime/WorkerRuntimeContext.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/runtime/WorkerRuntimeMaintenanceLoop.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/runtime/WorkerRuntimeOptions.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/runtime/WorkerRuntimeReporter.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/runtime/WorkerRuntimes.java`
- `sdk/xa-mass-java-sdk/src/test/java/com/xa/mass/client/JavaExternalSdkArchitectureGuardTest.java`

## Owner Review

Java external SDK owns the external worker runtime experience:

```text
worker runtime construction
worker-local handler registry
dispatch intake orchestration
result submission orchestration
runtime maintenance loop orchestration
worker-local event/evidence publishing API
lifecycle callbacks and bounded failure reporting
```

Server and worker-runtime own platform truth:

```text
WorkerGroup declaration and capability truth
Worker execution identity and membership
worker presence/reachability/readiness projection
worker-local evidence application
worker command lifecycle
scheduling eligibility and dispatch gates
```

Transport and adapters own delivery mechanics:

```text
protocol endpoint/session mechanics
delivery feasibility
dispatch/result wire exchange
connection/session evidence
```

The Java SDK must not treat protocol choice as worker capability ownership.
Protocol-specific implementations may decide how to exchange messages, but they
must not own `workerGroupId`, worker attributes, handler catalog, evidence
policy, or runtime maintenance semantics.

Worker handlers receive decoded worker invocation payload, not transport
delivery commands or task lifecycle records. The SDK runtime may keep the
minimum correlation needed to submit results, but that correlation is not
task/message/attempt identity and must not become scheduling, retry, lifecycle,
or business-input authority.

## Boundary Decision

Replace the shell-first `WorkerSessionSpec` direction with a worker runtime
capability model.

Target public concepts:

```text
WorkerRuntimeDefinition
  workerId
  workerGroupId
  attributes
  eventHandlers

WorkerSpec
  explicit registration projection from WorkerRuntimeDefinition
  transport mode hint

WorkerRuntime
  start / running / reporter / close
  owns common runtime orchestration

WorkerRuntimes
  polling or websocket protocol runtime selection/options
```

Target internal concepts:

```text
WorkerProtocolDriver
  protocol exchange only
  no worker capability ownership

WorkerDispatchProcessor
  current common invocation processor
  may be retained or renamed only if the owner meaning improves

WorkerRuntimeContext
  package-private common runtime derivation
  owns worker identity copy, immutable attributes/handlers, listener,
  executor, dispatch processor, and reporter wiring
  does not own protocol options

WorkerRuntimeOptions
  package-private common listener/executor wiring
  no polling interval, endpoint, reconnect, queue, or protocol fields

WorkerRuntimeMaintenanceLoop
  scheduled runtime upkeep only
  heartbeat first
  future worker-initiated evidence/report later
  never schedules platform tasks

WorkerRuntimeReporter
  explicit worker-local event/evidence publishing
```

Do not start by adding a new `WorkerRuntimeCore` wrapper around
`WorkerDispatchProcessor` unless it protects a real lifecycle/protocol seam.
The current processor already owns common handler invocation. The next useful
seam is around runtime definition, registration timing, protocol exchange, and
maintenance ownership.

Do not reintroduce a second public worker ability owner beside
`WorkerRuntimeDefinition`. The definition pivot has replaced
`WorkerSessionSpec`; future runtime shell work must preserve that single owner
instead of recreating worker ability setters on protocol-specific builders.

Worker command intake/ack is a real worker ability, but it is not part of the
next executable Java SDK slice. The old Java SDK command poll/ack surface has
been deleted and must not be reintroduced by this roadmap until worker-control
ownership defines a clean command contract.

## Target Public Shape

The public API should read as one worker ability model with different network
drivers:

```java
WorkerRuntimeDefinition worker = WorkerRuntimeDefinition.builder()
        .workerId("wkr-1")
        .workerGroupId("phone-device-probe")
        .attribute("region", "sg")
        .event("probe.phone.metadata", handler)
        .build();

mass.workers().registerWorker(WorkerSpec.polling(worker));

WorkerRuntime runtime = mass.workerRuntimes()
        .polling(worker)
        .pollInterval(Duration.ofMillis(200))
        .start();
```

WebSocket changes only the protocol driver:

```java
WorkerRuntime runtime = mass.workerRuntimes()
        .webSocket(worker)
        .endpoint(uri)
        .start();
```

Explicit worker-local evidence publishing is not hidden in `start()`:

```java
runtime.reporter().reportHandlerEvidence();
runtime.reporter().reportAvailable("ready");
runtime.reporter().reportDraining("operator-request");
```

The runtime may publish session presence and heartbeat for freshness. That is
not WorkerGroup capability truth and not worker-local handler evidence. Handler
evidence and bounded runtime evidence remain explicit caller/policy actions.

Handler invocation stays payload-first:

```java
WorkerRuntimeDefinition worker = WorkerRuntimeDefinition.builder()
        .workerId("wkr-1")
        .workerGroupId("phone-device-probe")
        .event("probe.phone.metadata", invocation -> {
    MassPayload input = invocation.input();
    MassPayload sharedConfig = invocation.sharedConfig();
    return WorkerResult.success(...);
})
        .build();
```

`taskId`, `messageId`, attempt, batch, retry, transport delivery id,
`DeliveryCommand`, route, adapter, endpoint, and session internals are not
handler-facing facts.

Runtime maintenance is local worker upkeep, not platform task scheduling:

```java
WorkerRuntime runtime = mass.workerRuntimes()
        .polling(worker)
        .heartbeatInterval(Duration.ofSeconds(10))
        .start();
```

The first maintenance task can be heartbeat. Later maintenance tasks may publish
worker-local evidence such as load, handler availability, readiness, or custom
runtime events. Those tasks must be explicit runtime maintenance policies, not
hidden protocol-specific startup side effects.

## Worker Ability Taxonomy

Use this taxonomy to classify every public or internal method before moving it:

| Ability | Owner | Current Examples | Protocol Responsibility |
| --- | --- | --- | --- |
| Declaration / registration | server + worker-runtime contract, invoked by SDK caller | `WorkerSpec`, `WorkerClient.registerWorker(...)` | none |
| Session presence | worker-runtime presence ingress, invoked by SDK runtime | `online`, `heartbeat`, `offline` | send freshness evidence |
| Runtime init | Java SDK runtime | build handlers, listener, result sink | create driver instance |
| Dispatch intake | Java SDK runtime orchestrates, driver exchanges | polling loop, WebSocket frame receive | receive/poll bytes or DTOs |
| Dispatch execution | Java SDK runtime | `WorkerDispatchProcessor`, `eventCode -> handler -> WorkerResult` | none |
| Result submit | Java SDK runtime orchestrates, driver exchanges | HTTP `submit-result`, WebSocket result frame | send result |
| Runtime maintenance loop | Java SDK runtime | heartbeat timer, future worker evidence report timer | send report when asked |
| Worker event/evidence report | Java SDK runtime API, server applies | `WorkerRuntimeReporter`, handler evidence, runtime evidence, availability, load | send report |
| Lifecycle control | Java SDK runtime | start, drain, stop, close | close protocol resources |
| Worker command intake / ack | worker-control owner, future Java SDK runtime ability | no current Java SDK public command surface | deferred |

Presence freshness must remain separate from WorkerGroup capability truth and
worker-local evidence truth.

## Model Budget And DTO Boundary

Do not create one DTO per internal layer. New models are allowed only when they
protect a real boundary:

```text
wire/API boundary
public handler-facing boundary
runtime owner boundary
protocol driver boundary
```

Same-JVM worker runtime layers should pass domain objects or typed values, not
serialize/deserialize through repeated wrapper models.

Allowed worker-runtime model families:

| Model Family | Owner | Purpose |
| --- | --- | --- |
| `WorkerRuntimeDefinition` | Java SDK worker runtime | worker ability truth |
| `WorkerSpec` | Java SDK worker registration boundary | declaration facts plus transport mode hint |
| worker protocol input DTO | current worker-api/protocol boundary | raw worker delivery message |
| `WorkerInvocation` | Java SDK handler/direct-poll boundary | result token plus event code and business payload |
| runtime correlation record | Java SDK runtime internal | result submission correlation, not task lifecycle truth |
| `WorkerResult` / `WorkerResultSubmission` | handler output and worker-api boundary | result publication |
| `WorkerRuntimeReporter` | Java SDK runtime/reporting boundary | worker-local evidence/report |

Forbidden model drift:

- Do not reintroduce `WorkerDispatchItem`, `DispatchContext`,
  `ResultCorrelationRef`, `WorkerEventHandlers`, `WorkerEventInvocation`, or
  `WorkerEventHandlerRuntime`.
- Do not expose `DeliveryCommand` to Java external worker SDK callers or
  handlers. `DeliveryCommand` is transport/adapter delivery intent.
- Do not keep `taskId`, `messageId`, `taskName`, `project`, `userId`, attempt,
  batch, retry, `rawItem`, route, adapter, endpoint, or session fields in
  handler-facing context.
- Do not JSON serialize/deserialize between SDK runtime layers to create fake
  separation.
- Do not reintroduce Java SDK public worker command DTOs as a shortcut for the
  future command runtime ability.
- Do not let tests preserve task-shaped worker invocation vocabulary as a
  second public API.

The Java SDK public worker model convergence already landed the payload-first
`WorkerInvocation` and opaque `resultCorrelationRef` boundary. Future JWR
slices must preserve that shape instead of recreating task-shaped worker
context.

## Non-Goals

- Do not change engine scheduling, assignment, retry, or result convergence.
- Do not redefine WorkerGroup capability truth.
- Do not move Java SDK code into `xa-mass-worker-runtime`.
- Do not introduce transport internals such as adapter id, route key,
  connection id, endpoint lease id, delivery queue key, or transport node id
  into the public worker runtime model.
- Do not build a cross-language adapter protocol in this roadmap. The model
  should be pressure-tested by that future, but this roadmap targets embedded
  Java SDK worker runtimes.
- Do not build a platform task scheduler or cron system. Runtime maintenance
  loops only perform local worker upkeep such as heartbeat and worker-initiated
  reports.
- Do not preserve `WorkerSessionSpec` as a compatibility alias after in-repo
  callers are migrated to the runtime definition shape.
- Do not reintroduce Java SDK public worker command poll/ack until the
  worker-control owner defines a successor contract.

## Do Not Start With

Do not start by making `WorkerRuntime` smaller or by renaming
`PollingWorkerRuntime` / `WebSocketWorkerRuntime`.

That repeats the previous mistake: it changes the visible lifecycle shell while
leaving capability ownership split by protocol. Start with the worker runtime
definition and registration boundary, then move concrete protocol
implementations under it.

Also do not start by introducing a same-module `WorkerRuntimeCore` wrapper that
only forwards to `WorkerDispatchProcessor`. The real current split is:

```text
definition / registration / maintenance / protocol exchange
```

not handler invocation.

## JWR-0 Inventory Refresh And Current-Fact Lock

Goal: update the roadmap inventory baseline after Java SDK public model
convergence.

Scope:

- `WorkerClient`
- `WorkerSpec`
- `WorkerHandlerEvidence`
- `WorkerRuntimeEvidence`
- `WorkerInvocation`
- `WorkerResultSubmission`
- `WorkerDispatchProcessor`
- `PollingWorkerRuntime`
- `WebSocketWorkerRuntime`
- `WorkerSessionSpec`
- `WorkerRuntimes`
- `WorkerRuntime*` listener/failure/startup types
- current scheduled loops: heartbeat, poll, reconnect, result sender
- scenario launcher and worker-pack callers
- public SDK docs and examples
- Java SDK architecture guard
- stale external worker evidence roadmap/docs

Acceptance:

- Add or update
  `JAVA_SDK_WORKER_RUNTIME_CAPABILITY_MODEL_CONVERGENCE_INVENTORY.md`.
- Inventory records that public DTO cleanup already landed and is no longer
  this roadmap's executable work.
- Inventory records that `WorkerSessionSpec` has been replaced by
  `WorkerRuntimeDefinition` and must not return as a compatibility alias.
- Inventory records current `start()` side effects:
  presence, heartbeat, poll, connection, result sender, and explicit absence of
  hidden registration.
- Inventory explicitly records that hidden handler/runtime evidence reporting
  is already removed from session start.
- Inventory distinguishes session presence from worker-local evidence reports.
- Inventory distinguishes runtime maintenance loops from platform tasks and
  from protocol receive/send loops.
- Inventory classifies command runtime ability as deferred because the Java SDK
  public command DTOs were removed.
- Inventory records that README examples use `WorkerResult.success(String)` /
  `WorkerResult.failure(resultCode, result)`.
- Inventory records that
  `EXTERNAL_WORKER_CAPABILITY_EVIDENCE_API_CONVERGENCE_ROADMAP.md` is
  superseded historical context and that JWR-6 owns future
  `reportHandlerEvidence(...)` / `reportRuntimeEvidence(...)` reporter
  decisions.

Verification candidates:

```bash
rg -n "registerWorker|online\\(|heartbeat\\(|offline|reportHandlerEvidence|reportRuntimeEvidence|submitResult|WorkerSessionSpec|WorkerInvocation|WorkerDispatchProcessor|PollingWorkerRuntime|WebSocketWorkerRuntime" sdk/xa-mass-java-sdk/src/main/java integrations -g "*.java"
rg -n "WorkerDispatchItem|DispatchContext|ResultCorrelationRef|WorkerEventHandlerRuntime|pollCommands|ackCommand|reportCapability|reportState|WorkerResult.success\\(Map" sdk/xa-mass-java-sdk/src/main/java sdk/xa-mass-java-sdk/README.md -g "*.java" -g "*.md"
```

## JWR-1 Worker Runtime Definition And Public Owner Pivot

Goal: establish one Java SDK owner for worker runtime ability facts and keep the
old session-shaped public owner removed.

Scope:

- Add or preserve `WorkerRuntimeDefinition` or equivalent.
- Keep worker ability facts in that definition:
  `workerId`, `workerGroupId`, attributes, event handlers, listener policy if
  it remains definition-owned.
- Keep public `WorkerSessionSpec` construction sites removed. Do not leave
  `WorkerRuntimeDefinition` and any session-shaped replacement as two live
  public ways to describe the same worker ability.
- Keep command handler registration out of this first slice.
- Add tests proving the definition is protocol-neutral.
- Add or adjust public docs to say polling and WebSocket workers have the same
  worker ability model.
- Update scenario launcher and worker-pack call sites that construct
  `WorkerSessionSpec`.

Acceptance:

- Worker ability facts are modeled once:
  `workerId`, `workerGroupId`, attributes, event handlers.
- `WorkerSessionSpec` is removed and not kept as package-private transitional
  glue, wrapper, or compatibility alias.
- Protocol-specific builder classes no longer independently own worker ability
  facts except as private construction delegates inside this slice.
- `WorkerRuntimeDefinition` does not include endpoint URL, poll interval,
  reconnect settings, adapter id, route key, session token, result queue
  capacity, or command handlers.
- WorkerGroup capability truth is not redefined by event handler registration.
  Handler registration is worker-local execution ability and optional evidence.
- A guard or reflection test fails if transport-internal identifiers enter the
  definition.
- Public examples show one worker definition reused by polling and WebSocket.
- Scenario launcher no longer treats polling and WebSocket workers as different
  capability owners.
- Worker-pack helpers do not duplicate worker ability fields across protocols.
- `sdk/xa-mass-java-sdk/README.md` uses
  `WorkerResult.success(String)` / `WorkerResult.failure(resultCode, result)`.

Verification candidates:

```bash
./mvnw -q -pl sdk/xa-mass-java-sdk test -Dtest=JavaExternalSdkArchitectureGuardTest,WorkerRuntimeDefinitionTest,WorkerClientTest,PollingWorkerRuntimeTest,WebSocketWorkerRuntimeTest
./mvnw -q -pl integrations/xa-mass-scenario-launcher,integrations/xa-mass-worker-pack -am -DskipTests compile
rg -n "adapterId|adapterNodeId|routeKey|connectionId|endpointLease|deliveryQueueKey|TransportAdapterBootstrap" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker -g "*.java"
rg -n "WorkerSessionSpec|WorkerResult.success\\(Map" sdk/xa-mass-java-sdk/README.md integrations sdk/xa-mass-java-sdk/src/main/java -g "*.java" -g "*.md"
```

## JWR-2 Explicit Registration Boundary

Goal: remove hidden worker registration from runtime start while preserving
session presence management.

Scope:

- Use the existing `WorkerSpec` registration boundary so callers explicitly
  register workers before runtime start. Do not add a same-shape
  `WorkerRegistrationSpec` unless a later boundary proves it carries different
  ownership.
- Retarget `PollingWorkerRuntime.start()` and `WebSocketWorkerRuntime.start()`
  so they do not call `WorkerClient.registerWorker(...)`.
- Keep `online`, `heartbeat`, and `offline` classification explicit:
  session freshness may remain runtime-managed.
- Keep handler/runtime evidence explicit through `WorkerClient` or future
  reporter APIs. Do not move evidence reporting back into `start()`.
- Update integration tests and examples to register first, then start runtime.
- Remove registration from runtime startup failure taxonomy.

Acceptance:

- `PollingWorkerRuntime.start()` or its replacement no longer calls
  `registerWorker`.
- `WebSocketWorkerRuntime.start()` or its replacement no longer calls
  `registerWorker`.
- `PollingWorkerRuntime.start()` may still call `online` and start heartbeat;
  that is session presence, not capability/evidence reporting.
- Public docs show registration as a separate action before runtime start.
- Tests cover start without implicit registration.
- `WorkerRuntimeStartupStep.REGISTER_WORKER` is removed or no longer reachable
  from runtime startup failure callbacks.
- Registration failures are reported by the explicit registration API, not as
  worker runtime startup failure events.
- Guards distinguish forbidden hidden registration from allowed presence
  freshness.

Verification candidates:

```bash
rg -n "registerWorker" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/runtime -g "*.java"
rg -n "REGISTER_WORKER|WorkerRuntimeStartupStep.REGISTER_WORKER" sdk/xa-mass-java-sdk/src/main/java sdk/xa-mass-java-sdk/src/test/java -g "*.java"
rg -n "reportCapability|reportState|pollCommands|ackCommand" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker -g "*.java"
./mvnw -q -pl sdk/xa-mass-java-sdk test -Dtest=PollingWorkerRuntimeTest,WebSocketWorkerRuntimeTest,WorkerClientTest,JavaExternalSdkArchitectureGuardTest
./mvnw -q -pl xa-mass-server -am test -Dtest=JavaExternalSdkPollingSessionIntegrationTest,ExternalWorkerRealtimeRegistrationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

## JWR-3 Runtime Shell And Driver API Cleanup

Goal: clean up the public runtime shell after JWR-1 has moved worker ability
ownership to `WorkerRuntimeDefinition`.

Scope:

- Add `MassPlatform.workerRuntimes()` or equivalent only if it materially
  improves public owner clarity.
- Decide whether concrete `PollingWorkerRuntime` / `WebSocketWorkerRuntime`
  remain public temporary protocol runtime classes or become internal protocol
  drivers behind `WorkerRuntime` builders.
- Replace public docs that center concrete protocol session classes with the
  runtime shell if that shell lands.

Acceptance:

- Public examples do not teach protocol-specific sessions as separate worker
  ability owners.
- Concrete polling/WebSocket classes, if still public, are documented as
  protocol runtimes over the same `WorkerRuntimeDefinition`, not separate
  worker definition models.
- No `WorkerSessionSpec` compatibility alias remains from JWR-1.

Verification candidates:

```bash
./mvnw -q -pl integrations/xa-mass-scenario-launcher,integrations/xa-mass-worker-pack -am -DskipTests compile
./mvnw -q -pl sdk/xa-mass-java-sdk test -Dtest=WorkerClientTest,PollingWorkerRuntimeTest,WebSocketWorkerRuntimeTest,JavaExternalSdkArchitectureGuardTest
rg -n "WorkerSessionSpec|PollingWorkerRuntime|WebSocketWorkerRuntime" sdk/xa-mass-java-sdk/README.md integrations -g "*.java" -g "*.md"
```

## JWR-4 Protocol Driver And Runtime Orchestration Split

Goal: make protocol-specific classes own network exchange while shared runtime
code owns invocation/result semantics.

Scope:

- Introduce package-private protocol-driver seams only where they protect real
  lifecycle/protocol boundaries.
- Keep or rename `WorkerDispatchProcessor` as the common invocation processor.
- Move duplicated result-submission outcome handling only where polling and
  WebSocket genuinely share the same lifecycle semantics.
- Keep protocol drivers responsible for:
  polling/receiving dispatch, sending results, reconnect/backoff mechanics,
  protocol resource close, and protocol frame/codecs.

Acceptance:

- Polling and WebSocket dispatch execution continue to use one common
  invocation processor.
- Protocol-specific classes do not inspect or own handler registration beyond
  receiving a runtime definition during construction.
- Protocol drivers own protocol exchange, not worker ability facts.
- Tests prove both polling and WebSocket route a dispatch through the same
  handler invocation semantics.
- There is no same-module wrapper that only forwards calls without protecting a
  lifecycle/protocol seam.

Verification candidates:

```bash
./mvnw -q -pl sdk/xa-mass-java-sdk test -Dtest=WorkerDispatchProcessorTest,PollingWorkerRuntimeTest,WebSocketWorkerRuntimeTest,JavaExternalSdkArchitectureGuardTest
rg -n "new WorkerDispatchProcessor|submitResult\\(|encodeResult|decodeDispatch|workerId\\(|workerGroupId\\(" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/runtime -g "*.java"
```

## JWR-5 Worker Runtime Maintenance Loop

Goal: model heartbeat and future worker-initiated reports as worker runtime
maintenance, not protocol-private loops and not platform tasks.

Scope:

- Preserve package-private `WorkerRuntimeMaintenanceLoop` or equivalent.
- Initial task: heartbeat/session freshness.
- Later-compatible task shape: worker-local evidence/report publication.
- Keep scheduling policy minimal: fixed interval, optional initial delay,
  bounded failure callback. No cron semantics, no platform task scheduling, no
  retry strategy beyond local loop error handling.
- The maintenance loop invokes the runtime reporter/driver; it does not call
  worker handlers and does not enqueue platform work.

Acceptance:

- Polling heartbeat is represented as a runtime maintenance task.
- No class named or documented as task/job/scheduler is introduced for this
  feature unless it is explicitly scoped as worker runtime maintenance.
- Maintenance tasks cannot execute event handlers or create platform tasks.
- Maintenance tasks publish through presence/reporter abstractions instead of
  direct protocol-specific calls where a shared abstraction is useful.
- Tests prove heartbeat maintenance runs independently from dispatch intake and
  does not consume or submit task items.

Verification candidates:

```bash
./mvnw -q -pl sdk/xa-mass-java-sdk test -Dtest=WorkerRuntimeMaintenanceLoopTest,PollingWorkerRuntimeTest,WebSocketWorkerRuntimeTest
rg -n "ScheduledExecutorService|scheduleWithFixedDelay|heartbeat" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/runtime -g "*.java"
```

## JWR-6 Worker Event Reporter

Goal: classify and expose worker active reporting as a worker runtime ability.

Scope:

- Preserve `WorkerRuntimeReporter` or equivalent if it protects a real
  public/runtime boundary.
- Keep `EXTERNAL_WORKER_CAPABILITY_EVIDENCE_API_CONVERGENCE_ROADMAP.md`
  superseded/historical. Do not execute it as a second active evidence roadmap.
- Map current public APIs into typed event categories:
  presence heartbeat, runtime state/readiness, handler availability,
  load/attributes evidence, offline/drain, custom runtime event if needed.
- Decide which events are runtime-managed maintenance policy and which require
  explicit caller invocation.
- Keep `reportHandlerEvidence` and `reportRuntimeEvidence` naming aligned with
  the external evidence API. Do not reintroduce `reportCapability` /
  `reportState` names in Java SDK public worker runtime.

Acceptance:

- Worker active reports are not hidden in `start()`.
- Heartbeat/presence is documented as session freshness, not capability truth.
- Heartbeat may be emitted by the maintenance loop; handler/runtime evidence
  reports are emitted through `WorkerRuntimeReporter`.
- State/readiness reports are explicit or policy-configured, not protocol-owned.
- Handler availability report, if supported, is derived from
  `WorkerRuntimeDefinition` but is not WorkerGroup capability truth.
- Active roadmap/docs no longer describe `:report-capability` or
  `WorkerCapabilityReport` as the current evidence model.
- Tests prove polling and WebSocket can use the same reporter interface if the
  interface lands in this slice.

Verification candidates:

```bash
./mvnw -q -pl sdk/xa-mass-java-sdk test -Dtest=WorkerRuntimeReporterTest,PollingWorkerRuntimeTest,WebSocketWorkerRuntimeTest,JavaExternalSdkArchitectureGuardTest
rg -n "reportCapability|reportState|reportHandlerEvidence|reportRuntimeEvidence|heartbeat|offline|drain" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker -g "*.java"
rg -n "report-capability|WorkerCapabilityReport|reportCapability" roadmap sdk xa-mass-server xa-mass-worker-runtime integrations -g "*.java" -g "*.md"
```

## JWR-7 Worker Command Runtime Ability - Deferred

Goal: keep worker command intake/ack visible as a future worker ability without
reintroducing the deleted Java SDK public command surface prematurely.

Scope:

- Inventory current server/embedded worker-control command owner.
- Decide whether external Java SDK managed runtimes should receive platform
  commands at all in the next product slice.
- If yes, define a successor command contract under worker-control ownership
  before adding Java SDK runtime handlers.
- If no, document command intake as out of scope for Java SDK worker runtime
  v1.

Acceptance:

- Java SDK public command DTOs are not reintroduced in this roadmap before the
  worker-control owner decision.
- `WorkerRuntimeDefinition` first slice does not include command handlers.
- Any future command handler model is protocol-neutral and does not copy old
  `WorkerCommand*` DTOs into a new package.

Verification candidates:

```bash
rg -n "WorkerCommand|pollCommands|ackCommand|onCommand|commandHandlers" sdk/xa-mass-java-sdk/src/main/java roadmap/JAVA_SDK_WORKER_RUNTIME_CAPABILITY_MODEL_CONVERGENCE_ROADMAP.md -g "*.java" -g "*.md"
```

## JWR-8 Guards, Docs, And Residue

Goal: prevent the old protocol-owned capability model from returning.

Scope:

- Update `sdk/README.md`.
- Update `sdk/xa-mass-java-sdk/README.md`.
- Update `doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md`.
- Archive or supersede stale external worker evidence roadmap docs that still
  describe `report-capability` as current.
- Add architecture guard checks for:
  definition field allowlist, protocol-driver import boundaries, no implicit
  registration in runtime start, no hidden handler/runtime evidence reporting
  in runtime start, and no transport internals in public worker runtime
  contracts.
- Archive or supersede stale `WorkerRuntime` direction docs after residue scan.

Acceptance:

- Public SDK docs say polling and WebSocket workers are the same worker runtime
  ability model with different protocol drivers.
- Active roadmaps do not describe `WorkerSessionSpec` as the target shape.
- Active roadmaps do not describe `:report-capability` /
  `WorkerCapabilityReport` as the current Java SDK evidence model.
- README examples use `WorkerResult.success(String)` and do not preserve
  old `WorkerResult.success(Map.of(...))` examples.
- Architecture guard fails if public worker runtime contracts expose
  transport-internal identifiers.
- Architecture guard fails if protocol driver classes own worker definition
  facts.
- Architecture guard references the completed Java SDK public worker model
  convergence for handler-facing task-field, `rawItem`, and transport
  `DeliveryCommand` residue.
- Source scans show `start()` no longer performs hidden registration or
  handler/runtime evidence reporting.

Verification candidates:

```bash
./mvnw -q -pl sdk/xa-mass-java-sdk test -Dtest=JavaExternalSdkArchitectureGuardTest,WorkerRuntimeDefinitionTest,WorkerRuntimeContextTest,WorkerDispatchProcessorTest
rg -n "WorkerSessionSpec|reportCapability|reportState|registerWorker" roadmap sdk/xa-mass-java-sdk/README.md sdk/README.md doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md -g "*.md"
rg -n "adapterId|adapterNodeId|routeKey|connectionId|endpointLease|deliveryQueueKey|TransportAdapterBootstrap" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker -g "*.java"
```

## Suggested Implementation Order

1. JWR-0 inventory refresh and current-fact lock. Landed.
2. JWR-1 worker runtime definition and public owner pivot. Landed.
3. JWR-2 explicit registration boundary. Landed.
4. JWR-3 runtime shell and driver API cleanup. Landed.
5. JWR-5 worker runtime maintenance loop. Landed for polling heartbeat.
6. JWR-6 worker runtime reporter. Landed as `WorkerRuntimeReporter`.
7. JWR-4 protocol driver and runtime orchestration split. Landed for polling
   worker-api exchange and WebSocket connect/frame exchange.
8. Common runtime fact derivation has landed as package-private
   `WorkerRuntimeContext` / `WorkerRuntimeOptions`; protocol options remain in
   concrete runtime builders.
9. JWR-7 worker command runtime ability decision. Deferred by owner decision;
   do not reintroduce command DTOs until worker-control owns a successor
   contract.
10. JWR-8 final guards/docs/residue. Landed for Java external SDK and direct
   JWR companion docs; portfolio-wide roadmap archive cleanup remains follow-up.

JWR-5 can start with heartbeat only. JWR-6 can be implemented after JWR-2 or
JWR-5 depending on whether evidence/reporting becomes a public caller need
before the driver split. JWR-7 must remain deferred until worker-control owner
truth is settled; it should not block runtime definition or registration
cleanup.

## Roadmap Completion Criteria

This roadmap can be marked complete only when:

- Polling and WebSocket workers share one worker runtime definition model.
- Worker ability facts are not owned by protocol-specific builders/classes.
- `WorkerSessionSpec` is not a second public worker ability owner or
  compatibility alias.
- Java SDK public worker model convergence remains enforced:
  no `WorkerDispatchItem`, `DispatchContext`, `ResultCorrelationRef`,
  `WorkerEventHandlers`, `WorkerEventHandlerRuntime`, public task-shaped
  handler context, or transport `DeliveryCommand` imports.
- `DeliveryCommand` remains transport/adapter-owned and is not imported or
  exposed by Java external worker SDK runtime/handler contracts.
- Worker runtime start no longer hides worker registration.
- Registration failure is not reported as a runtime startup step after
  registration is made explicit.
- Worker runtime start does not hide handler/runtime evidence reporting.
- Session presence/heartbeat is documented as freshness evidence, not
  WorkerGroup capability truth.
- Worker runtime maintenance loops are modeled as local upkeep and do not
  become platform task scheduling.
- Worker active reporting has an explicit owner and public shape if it remains
  in Java SDK runtime.
- Worker command intake/ack is either classified as a deferred worker-control
  follow-up or implemented through a successor common runtime contract.
- Protocol drivers are internal network exchange implementations, not worker
  capability owners.
- Polling and WebSocket runtime shells use package-private
  `WorkerRuntimeContext` for common worker identity, immutable ability facts,
  listener/executor wiring, dispatch processor, and reporter wiring; they do
  not directly reconstruct those facts from `WorkerRuntimeDefinition`.
- Public docs and examples present polling and WebSocket as the same background
  worker runtime with different protocol drivers.
- Public docs and examples use the current `WorkerResult.success(String)` /
  `WorkerResult.failure(resultCode, result)` shape.
- Active evidence roadmaps/docs use `reportHandlerEvidence(...)` /
  `reportRuntimeEvidence(...)` wording or are archived as superseded.
- Java SDK architecture guards prevent transport-internal identifiers from
  entering public worker runtime contracts.
- In-repo adopters compile against the final shape.
- Stale `WorkerRuntime` direction docs are archived or explicitly marked
  superseded.

## Open Decisions

- Final naming: `WorkerRuntimeDefinition` vs `WorkerDefinition`.
- Whether `WorkerRuntime` should later expose direct `report(...)` / `drain(...)`
  convenience methods. Current code uses `WorkerRuntime.reporter()` and
  `WorkerRuntimeReporter`.
- Whether `WorkerSpec` should be renamed later remains a naming cleanup only;
  this roadmap does not add a parallel `WorkerRegistrationSpec`.
- Whether protocol-specific concrete classes remain public during convergence
  or become package-private driver implementations after callers move.
- Whether command intake belongs in Java SDK managed worker runtime v1, and if
  so, what worker-control successor contract owns it.
