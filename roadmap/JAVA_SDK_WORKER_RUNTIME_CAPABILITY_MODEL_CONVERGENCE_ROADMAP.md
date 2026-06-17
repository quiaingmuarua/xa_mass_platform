# Java SDK Worker Runtime Capability Model Convergence Roadmap

Status: proposed direction document.

## Summary

The Java external SDK currently exposes polling and WebSocket worker sessions as
two concrete classes. Both classes are long-running worker runtimes in practice:
they register a worker, maintain presence or connectivity, receive dispatch,
invoke event handlers, submit results, run runtime maintenance loops, and report
worker-local evidence. The network model differs, but the worker abilities are
the same.

The previous `WorkerSession` convergence only made a narrow lifecycle shell. It
did not establish a common owner for worker abilities, so polling and WebSocket
could still keep separate capability, registration, reporting, command, and
runtime semantics. This roadmap supersedes that shell-first direction.

Target principle:

```text
WorkerRuntime owns worker abilities.
Protocol drivers own only network exchange mechanics.
Event handlers own business execution.
Server / worker-runtime own platform worker truth and evidence application.
```

Polling workers are not one-shot pull clients. WebSocket workers are not a
separate capability class. Both are background worker runtimes with different
protocol drivers.

## Current Code Observations

Current code facts verified from `sdk/xa-mass-java-sdk`:

- `PollingWorkerSession.start()` currently performs:
  `registerWorker -> online -> reportCapability -> reportState ->
  heartbeat loop -> poll loop`.
- `WebSocketWorkerSession.start()` currently performs:
  `registerWorker -> connect WebSocket -> result sender loop`.
- `WorkerSessionSpec` currently carries `workerId`, `workerGroupId`,
  `attributes`, event handlers, and listener. That makes a session-shaped DTO
  carry worker definition facts.
- `WorkerClient` already exposes separate routes for:
  worker registration, presence, poll, submit result, command poll/ack,
  capability report, and state report.
- `WorkerCommandPollResult`, `WorkerCommandAck`, and related DTOs exist, but
  managed worker sessions do not yet model command intake/ack as a common worker
  runtime ability.
- `PollingWorkerSession` currently owns heartbeat scheduling directly inside
  its session implementation. There is no common worker runtime maintenance
  loop model for heartbeat now or future worker-initiated reports.
- Java external SDK `WorkerDispatchItem` has been narrowed to worker wire
  payload plus opaque `resultCorrelationRef`. It is not handler-facing.
- `DispatchContext` has been removed. Handler-facing execution now uses
  `WorkerInvocation(eventCode, input, sharedConfig)`, with result correlation
  kept opaque in the session/runtime path.
- Transport `DeliveryCommand` is already the assigned-delivery transport intent:
  `deliveryBucketId`, `selectedWorkerId`, opaque `payload`, and
  `correlationRef`. It belongs to transport/adapters. Java worker runtime and
  handlers should not understand or depend on `DeliveryCommand`.
- `WorkerEventHandlerRuntime` is already protocol-neutral and can remain the
  business-handler invocation component.
- `WorkerSessionListener` is a broad union callback sink. It is useful as
  current observability, but it is not proof of a clean worker runtime event
  taxonomy.

Representative current files:

- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session/PollingWorkerSession.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session/WebSocketWorkerSession.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session/WorkerSessionSpec.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerClient.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/handler/WorkerEventHandlerRuntime.java`

## Owner Review

Java external SDK owns the external worker runtime experience:

```text
worker runtime construction
worker-local handler registry
dispatch intake orchestration
worker command intake and ack orchestration
result submission orchestration
runtime maintenance loop orchestration
worker-local event/evidence publishing API
lifecycle callbacks and failure reporting
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
must not own `workerGroupId`, worker attributes, handler catalog, command
handling, or event/evidence reporting semantics.

Worker handlers should receive the decoded worker invocation payload, not
transport delivery commands or task lifecycle records. The SDK runtime may keep
correlation data internally so it can submit results, but that correlation is
not handler-facing worker capability truth.

## Boundary Decision

Replace the shell-first `WorkerSession` direction with a worker-runtime
capability model.

Target public concepts:

```text
WorkerRuntimeDefinition
  workerId
  workerGroupId
  attributes
  eventHandlers
  commandHandlers

WorkerRegistrationSpec
  WorkerRuntimeDefinition identity/declaration facts
  transport mode hint

WorkerRuntime
  start / running / report / drain / close
  owns common runtime orchestration

WorkerRuntimeTransport
  polling or websocket protocol driver selection/options
```

Target internal concepts:

```text
WorkerProtocolDriver
  protocol exchange only
  no worker capability ownership

WorkerRuntimeCore
  protocol payload + internal correlation -> worker invocation -> handler -> result
  command -> command handler -> ack
  maintenance task -> event/evidence/report
  runtime event/evidence -> reporter

WorkerRuntimeMaintenanceTask
  scheduled runtime upkeep only
  heartbeat first
  future worker-initiated evidence/report later
  never schedules platform tasks

WorkerEventReporter
  explicit worker-local event/evidence publishing
```

The names may change during implementation if a better local naming pattern is
found, but the owner split must not change.

## Target Public Shape

The public API should read as one worker ability model with different network
drivers:

```java
WorkerRuntimeDefinition worker = WorkerRuntimeDefinition.builder()
        .workerId("wkr-1")
        .workerGroupId("phone-device-probe")
        .attribute("region", "sg")
        .onEvent("probe.phone.metadata", handler)
        .onCommand("drain", drainHandler)
        .build();

mass.workers().register(WorkerRegistrationSpec.polling(worker));

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
runtime.report(WorkerRuntimeEvent.available("ready"));
runtime.report(WorkerRuntimeEvent.handlerAvailability(worker.eventCodes()));
runtime.drain("operator-request");
```

The runtime may publish protocol-required presence or heartbeat, but
`reportCapability` and state/evidence publication must be explicit policy, not
an accidental side effect of starting a session.

Handler invocation should be payload-first:

```java
worker.onEvent("probe.phone.metadata", invocation -> {
    MassPayload input = invocation.input();
    MassPayload sharedConfig = invocation.sharedConfig();
    return WorkerResult.success(...);
});
```

`taskId`, `messageId`, attempt, batch, retry, transport delivery id,
`DeliveryCommand`, route, adapter, and endpoint details are not handler-facing
facts. The runtime keeps the minimum internal correlation needed to submit a
result.

Runtime maintenance is a worker-runtime concern, not platform task scheduling:

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

| Ability | Owner | Examples | Protocol Responsibility |
| --- | --- | --- | --- |
| Declaration / registration | server + worker-runtime contract, invoked by SDK caller | `registerWorker`, group membership, transport mode hint, static attributes | none |
| Runtime init | Java SDK runtime | build handlers, command handlers, listener, result sink | create driver instance |
| Dispatch intake | Java SDK runtime orchestrates, driver exchanges | polling loop, WebSocket frame receive | receive/poll bytes or DTOs |
| Dispatch execution | Java SDK runtime | `eventCode -> handler -> WorkerResult` | none |
| Result submit | Java SDK runtime orchestrates, driver exchanges | `submit-result`, result frame send | send result |
| Worker command intake / ack | Java SDK runtime | poll/receive command, command handler, ack | receive/send command messages |
| Runtime maintenance loop | Java SDK runtime | heartbeat timer, future worker evidence report timer | send protocol report when asked |
| Worker event/evidence report | Java SDK runtime API, server applies | heartbeat, state, handler availability, load, offline, custom runtime events | send report |
| Lifecycle control | Java SDK runtime | start, drain, stop, close | close protocol resources |

Heartbeat may be implemented through the same event/evidence reporter, but
presence freshness must remain separate from WorkerGroup capability truth.

## Model Budget And DTO Boundary

Do not create one DTO per internal layer. New models are allowed only when they
protect a real boundary:

```text
wire/API boundary
public handler-facing boundary
runtime owner boundary
protocol driver boundary
```

Same-JVM worker runtime layers must pass domain objects or typed values, not
serialize/deserialize through repeated wrapper models.

Allowed worker-runtime model families:

| Model Family | Owner | Purpose |
| --- | --- | --- |
| `WorkerRuntimeDefinition` | Java SDK worker runtime | worker ability truth |
| worker protocol input DTO | current worker-api/protocol boundary | raw worker delivery message, temporary until narrowed |
| worker invocation context | Java SDK handler boundary | event code plus business payload only |
| runtime correlation record | Java SDK runtime internal | result submission correlation, not handler-facing |
| `WorkerResult` / submit request | handler output and worker-api boundary | result publication |
| `WorkerRuntimeEvent` | Java SDK runtime/reporting boundary | worker-local evidence/report |
| `WorkerCommand` / ack | worker command boundary | command handling and acknowledgement |

Forbidden model drift:

- Do not introduce `RuntimeDispatchEnvelope`, `ProtocolDispatchCommand`,
  `WorkerSessionDispatch`, or similar same-process dispatch wrappers unless
  they replace a real external/public boundary.
- Do not expose `DeliveryCommand` to Java external worker SDK callers or
  handlers. `DeliveryCommand` is transport/adapter delivery intent.
- Do not keep `taskId`, `messageId`, `taskName`, `project`, `userId`, attempt,
  batch, retry, `rawItem`, route, adapter, endpoint, or session fields in the
  handler-facing context.
- Do not JSON serialize/deserialize between SDK runtime layers to create fake
  separation.
- Do not let tests preserve task-shaped worker invocation vocabulary as a second
  public API.

`WorkerDispatchItem` is therefore a wire/protocol DTO, not the handler model.
The old `DispatchContext` convergence was completed by the archived
`doc/archive/sdk/2026-06-17_EXTERNAL_WORKER_INVOCATION_PAYLOAD_BOUNDARY_CONVERGENCE_ROADMAP.md`;
future JWR slices must preserve the payload-first `WorkerInvocation` and opaque
`resultCorrelationRef` boundary instead of recreating task-shaped worker
context.

## Non-Goals

- Do not change engine scheduling, assignment, retry, or result convergence.
- Do not redefine WorkerGroup capability truth.
- Do not move Java SDK code into `xa-mass-worker-runtime`.
- Do not introduce transport internals such as adapter id, route key,
  connection id, endpoint lease id, delivery queue key, or transport node id into
  the public worker runtime model.
- Do not build a cross-language adapter protocol in this roadmap. The model
  should be pressure-tested by that future, but this roadmap targets embedded
  Java SDK worker runtimes.
- Do not build a platform task scheduler or cron system. Runtime maintenance
  loops only perform local worker upkeep such as heartbeat and worker-initiated
  reports.
- Do not preserve the current `WorkerSessionSpec` shape as a compatibility
  alias if the new model lands. This repo has no internal compatibility burden
  for superseded pre-release paths.

## Do Not Start With

Do not start by making `WorkerSession` smaller or by renaming
`PollingWorkerSession` / `WebSocketWorkerSession`.

That repeats the previous mistake: it changes the visible lifecycle shell while
leaving capability ownership split by protocol. Start with inventory and the
worker ability model, then move concrete protocol implementations under it.

Also do not start by adding another polling-only scheduled executor abstraction.
Heartbeat and later active reports must be classified as worker runtime
maintenance, so WebSocket can reuse the same semantic owner even if the wire
mechanics differ.

## JWR-0 Inventory And Classification

Goal: classify current Java SDK worker session APIs by worker ability.

Scope:

- `WorkerClient`
- `WorkerSpec`
- `WorkerCapabilityReport`
- `WorkerStateReport`
- `WorkerCommand*`
- `WorkerEventHandlers`
- `WorkerEventHandlerRuntime`
- `WorkerDispatchItem`
- `WorkerInvocation`
- `PollingWorkerSession`
- `WebSocketWorkerSession`
- `WorkerSession*` listener/failure/startup types
- current scheduled loops: heartbeat, poll, reconnect, result sender
- scenario launcher and worker-pack callers
- public SDK docs and examples

Acceptance:

- Add `JAVA_SDK_WORKER_RUNTIME_CAPABILITY_MODEL_CONVERGENCE_INVENTORY.md`.
- Every current worker-session method and builder field is classified into the
  ability taxonomy above.
- Inventory separates public API, implementation detail, test fixture, and
  stale/superseded docs.
- Inventory explicitly records that current `WorkerSessionSpec` carries worker
  definition facts and is not the target model.
- Inventory explicitly records that `WorkerDispatchItem` is a wire/protocol DTO
  and `WorkerInvocation` is the handler-facing worker invocation model.
- Inventory names all current implicit `start()` side effects:
  registration, presence, capability report, state report, heartbeat, poll,
  connection, result sender.
- Inventory distinguishes runtime maintenance loops from platform tasks and
  from protocol receive/send loops.

Verification candidates:

```bash
rg -n "registerWorker|online\\(|heartbeat\\(|offline|reportCapability|reportState|pollCommands|ackCommand|submitResult|WorkerSessionSpec|WorkerDispatchItem|WorkerInvocation|PollingWorkerSession|WebSocketWorkerSession" sdk/xa-mass-java-sdk/src/main/java integrations -g "*.java"
```

## JWR-1 Worker Runtime Definition Contract

Goal: establish one Java SDK owner for worker abilities.

Scope:

- Add `WorkerRuntimeDefinition` or equivalent.
- Add event handler and command handler registration to that definition.
- Add tests that prove the definition is protocol-neutral.
- Add or adjust public docs to say polling and WebSocket workers have the same
  worker ability model.

Acceptance:

- Worker ability facts are modeled once:
  `workerId`, `workerGroupId`, attributes, event handlers, command handlers.
- Protocol-specific builder classes no longer own independent copies of worker
  ability facts except as transitional construction delegates within the same
  slice.
- `WorkerRuntimeDefinition` does not include endpoint URL, poll interval,
  reconnect settings, adapter id, route key, session token, or result queue
  capacity.
- WorkerGroup capability truth is not redefined by event handler registration.
  Handler registration is worker-local execution ability and optional evidence.
- A guard or reflection test fails if transport-internal identifiers enter the
  definition.

Verification candidates:

```bash
./mvnw -q -pl sdk/xa-mass-java-sdk -am test -Dtest=JavaExternalSdkArchitectureGuardTest,WorkerRuntimeDefinitionTest -Dsurefire.failIfNoSpecifiedTests=false
rg -n "adapterId|adapterNodeId|routeKey|connectionId|endpointLease|deliveryQueueKey|TransportAdapterBootstrap" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker -g "*.java"
```

## JWR-2 Worker Invocation Payload Boundary Dependency

Goal: keep the worker runtime capability roadmap aligned with the completed
payload-first handler invocation and opaque result-correlation boundary.

The executable payload/correlation work was owned by:

```text
doc/archive/sdk/2026-06-17_EXTERNAL_WORKER_INVOCATION_PAYLOAD_BOUNDARY_CONVERGENCE_ROADMAP.md
```

That roadmap has landed:

- `DispatchContext` removal
- `WorkerDispatchItem` containment as wire/protocol DTO
- handler-facing `WorkerInvocation(eventCode, input, sharedConfig)`
- opaque `ResultCorrelationRef`
- polling and WebSocket result submission correlation
- Java SDK worker handler migration
- server external worker API wire migration to `resultCorrelationRef`
- scenario launcher and worker-pack integration migration
- guards that prevent task-shaped handler context or transport
  `DeliveryCommand` from entering Java SDK worker handlers

Acceptance in this roadmap:

- JWR implementation does not add new task-shaped handler context fields.
- JWR implementation does not introduce `DeliveryCommand` into Java external
  worker SDK runtime or handler contracts.
- Before JWR-4 moves common runtime execution, it must reuse
  `WorkerInvocation` and `ResultCorrelationRef`; it must not recreate
  `DispatchContext` or task-shaped result correlation.
- JWR docs and examples reference the dedicated payload roadmap instead of
  duplicating its detailed field-level work.

## JWR-3 Explicit Registration And Evidence Boundary

Goal: remove hidden registration and worker-local evidence reporting from
runtime start.

Scope:

- Add `WorkerRegistrationSpec` or narrow existing `WorkerSpec` usage so callers
  explicitly register workers before runtime start.
- Move `reportCapability` and `reportState` out of `PollingWorkerSession.start()`.
- Keep presence/heartbeat classification explicit:
  protocol-required presence may remain runtime-managed, but capability/state
  evidence policy must be caller-visible or configured through an explicit
  `WorkerEventReporter`.
- Update integration tests and examples to register first, then start runtime.

Acceptance:

- `PollingWorkerSession.start()` or its replacement no longer calls
  `registerWorker`, `reportCapability`, or `reportState`.
- `WebSocketWorkerSession.start()` or its replacement no longer calls
  `registerWorker`.
- Public docs show registration as a separate action.
- Current `:report-capability` usage is either explicit caller code or owned by
  `EXTERNAL_WORKER_CAPABILITY_EVIDENCE_API_CONVERGENCE_ROADMAP.md`; it is not a
  hidden runtime start side effect.
- Tests cover start without implicit registration/reporting.

Verification candidates:

```bash
rg -n "registerWorker|reportCapability|reportState" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session -g "*.java"
./mvnw -q -pl sdk/xa-mass-java-sdk -am test -Dtest=PollingWorkerSessionTest,WebSocketWorkerSessionTest,WorkerClientTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -q -pl xa-mass-server -am test -Dtest=JavaExternalSdkPollingSessionIntegrationTest,ExternalWorkerRealtimeRegistrationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

## JWR-4 Common Worker Runtime Core And Protocol Driver SPI

Goal: make protocol-specific classes implement network exchange only.

Scope:

- Introduce package-private `WorkerProtocolDriver`.
- Introduce package-private `WorkerRuntimeCore`.
- Move common dispatch execution and result submission orchestration into the
  core.
- Keep protocol drivers responsible for:
  polling/receiving dispatch, sending results, publishing reports, command
  poll/receive, command ack, and protocol resource close.

Target internal shape:

```java
interface WorkerProtocolDriver extends AutoCloseable {
    void start(WorkerProtocolContext context);
    void requestDispatch();
    void submitResult(WorkerResultEnvelope result);
    void report(WorkerRuntimeEvent event);
    void ackCommand(WorkerCommandAckEnvelope ack);
    void close();
}
```

The actual method names can be refined during implementation. The required
boundary is that the driver has no handler catalog, worker group membership
truth, worker attributes ownership, or capability policy.

Acceptance:

- Polling and WebSocket dispatch execution use one common processor/core.
- Polling and WebSocket result submission use one common runtime outcome path,
  with protocol-specific send mechanics below it.
- Protocol drivers do not inspect or own handler registration.
- Tests prove both polling and WebSocket route a dispatch through the same
  handler invocation semantics.
- There is no same-module wrapper that only forwards calls without protecting a
  lifecycle/protocol seam.

Verification candidates:

```bash
./mvnw -q -pl sdk/xa-mass-java-sdk -am test -Dtest=WorkerEventHandlerRuntimeTest,WorkerRuntimeCoreTest,PollingWorkerSessionTest,WebSocketWorkerSessionTest -Dsurefire.failIfNoSpecifiedTests=false
rg -n "new WorkerEventHandlerRuntime|handlerRuntime.invoke|submitResult\\(" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session -g "*.java"
```

## JWR-5 Worker Runtime Maintenance Loop

Goal: model heartbeat and future worker-initiated reports as worker runtime
maintenance, not protocol-private loops and not platform tasks.

Scope:

- Add package-private `WorkerRuntimeMaintenanceTask`,
  `WorkerRuntimeMaintenanceLoop`, or equivalent.
- Initial task: heartbeat/session freshness.
- Later-compatible task shape: worker-local evidence/report publication.
- Keep scheduling policy minimal: fixed interval, optional initial delay, bounded
  failure callback. No cron semantics, no task scheduling, no retry strategy
  beyond local loop error handling.
- The maintenance loop invokes the runtime reporter/driver; it does not call
  worker handlers and does not enqueue platform work.

Target internal shape:

```java
interface WorkerRuntimeMaintenanceTask {
    String name();
    Duration interval();
    void run(WorkerRuntimeContext context) throws Exception;
}
```

The final names can change. The required contract is that a maintenance task is
local worker upkeep only.

Acceptance:

- Polling heartbeat is represented as a runtime maintenance task or is clearly
  prepared to move into that model in the same slice.
- No class named or documented as task/job/scheduler is introduced for this
  feature unless it is explicitly scoped as worker runtime maintenance.
- Maintenance tasks cannot execute event handlers or create platform tasks.
- Maintenance tasks publish through `WorkerEventReporter` / protocol driver
  abstractions instead of calling protocol-specific HTTP/WebSocket clients
  directly.
- Tests prove the heartbeat maintenance task runs independently from dispatch
  intake and does not consume or submit task items.

Verification candidates:

```bash
./mvnw -q -pl sdk/xa-mass-java-sdk -am test -Dtest=WorkerRuntimeMaintenanceLoopTest,PollingWorkerSessionTest,WebSocketWorkerSessionTest -Dsurefire.failIfNoSpecifiedTests=false
rg -n "ScheduledExecutorService|scheduleWithFixedDelay|heartbeat" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session -g "*.java"
```

## JWR-6 Worker Event Reporter

Goal: classify and expose worker active reporting as a worker runtime ability.

Scope:

- Add `WorkerRuntimeEvent` / `WorkerEventReporter` or equivalent.
- Map current public APIs into typed event categories:
  presence heartbeat, state/readiness, handler availability, load/attributes
  evidence, offline/drain, custom runtime event if needed.
- Decide which events are runtime-managed maintenance policy and which require
  explicit caller invocation.
- Keep `reportCapability` naming under the external capability evidence
  roadmap; do not promote it as the generic event model name.

Acceptance:

- Worker active reports are not hidden in `start()`.
- Heartbeat/presence is documented as session freshness, not capability truth.
- Heartbeat may be emitted by the maintenance loop, but the reporter owns the
  event shape.
- State/readiness reports are explicit or policy-configured, not protocol-owned.
- Handler availability report, if supported, is derived from
  `WorkerRuntimeDefinition` but is not WorkerGroup capability truth.
- Tests prove polling and WebSocket can use the same reporter interface even if
  one protocol has a narrower implementation in this slice.

Verification candidates:

```bash
./mvnw -q -pl sdk/xa-mass-java-sdk -am test -Dtest=WorkerEventReporterTest,PollingWorkerSessionTest,WebSocketWorkerSessionTest -Dsurefire.failIfNoSpecifiedTests=false
rg -n "reportCapability|reportState|heartbeat|offline|drain" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker -g "*.java"
```

## JWR-7 Worker Command Runtime Ability

Goal: make worker command intake and ack a common runtime ability instead of an
HTTP polling side feature.

Scope:

- Add command handler registration to `WorkerRuntimeDefinition`.
- Add command processor and ack outcome model.
- Polling driver uses current `pollCommands` / `ackCommand`.
- WebSocket driver maps command frames if the protocol supports them; if not,
  record a protocol limitation without changing the common worker ability model.

Acceptance:

- Command handling is defined once at the worker runtime level.
- Protocol-specific drivers only receive/send command messages.
- `WorkerCommand*` DTOs are not copied into a second protocol-specific model
  unless the wire codec genuinely requires it.
- Tests cover command handler success, rejection/failure, and ack emission.

Verification candidates:

```bash
./mvnw -q -pl sdk/xa-mass-java-sdk -am test -Dtest=WorkerCommandRuntimeTest,PollingWorkerSessionTest,WebSocketWorkerSessionTest -Dsurefire.failIfNoSpecifiedTests=false
```

## JWR-8 Public API And Adopter Migration

Goal: move public users from concrete protocol session ownership to worker
runtime ownership.

Scope:

- Add `MassPlatform.workerRuntimes()` or equivalent.
- Migrate scenario launcher and worker-pack to:
  `register definition -> start runtime`.
- Replace public docs that center `WorkerSessionSpec` with
  `WorkerRuntimeDefinition`.
- Decide whether concrete `PollingWorkerSession` / `WebSocketWorkerSession`
  remain public temporary protocol runtime classes or are folded behind
  `WorkerRuntime` builders.

Acceptance:

- Public examples show one worker definition reused by polling and WebSocket.
- Scenario launcher no longer treats polling and WebSocket workers as different
  capability owners.
- Worker-pack helpers do not duplicate worker ability fields across protocols.
- `WorkerSessionSpec` is removed or marked as superseded and no production
  caller depends on it.
- No compatibility alias remains if all in-repo callers are migrated.

Verification candidates:

```bash
./mvnw -q -pl integrations/xa-mass-scenario-launcher,integrations/xa-mass-worker-pack -am -DskipTests compile
./mvnw -q -pl sdk/xa-mass-java-sdk -am test -Dtest=WorkerClientTest,PollingWorkerSessionTest,WebSocketWorkerSessionTest,JavaExternalSdkArchitectureGuardTest -Dsurefire.failIfNoSpecifiedTests=false
rg -n "WorkerSessionSpec|PollingWorkerSession|WebSocketWorkerSession" sdk/xa-mass-java-sdk/README.md integrations -g "*.java" -g "*.md"
```

## JWR-9 Guards, Docs, And Residue

Goal: prevent the old protocol-owned capability model from returning.

Scope:

- Update `sdk/README.md`.
- Update `sdk/xa-mass-java-sdk/README.md`.
- Update `doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md`.
- Add architecture guard checks for:
  definition field allowlist, protocol-driver import boundaries, no implicit
  registration/reporting in runtime start, and no transport internals in public
  worker runtime contracts.
- Archive or supersede stale `WorkerSession` direction docs after residue scan.

Acceptance:

- Public SDK docs say polling and WebSocket workers are the same worker runtime
  ability model with different protocol drivers.
- Active roadmaps do not describe `WorkerSessionSpec` as the target shape.
- Architecture guard fails if public worker runtime contracts expose
  transport-internal identifiers.
- Architecture guard fails if protocol driver classes own worker definition
  facts.
- Architecture guard references the dedicated invocation payload roadmap for
  handler-facing task-field, `rawItem`, and transport `DeliveryCommand`
  residue.
- Source scans show `start()` no longer performs hidden registration or
  capability/state evidence reporting.

Verification candidates:

```bash
./mvnw -q -pl sdk/xa-mass-java-sdk -am test -Dtest=JavaExternalSdkArchitectureGuardTest,WorkerRuntimeDefinitionTest,WorkerRuntimeCoreTest,WorkerEventReporterTest -Dsurefire.failIfNoSpecifiedTests=false
rg -n "WorkerSessionSpec|reportCapability|reportState|registerWorker" roadmap sdk/xa-mass-java-sdk/README.md sdk/README.md doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md -g "*.md"
rg -n "adapterId|adapterNodeId|routeKey|connectionId|endpointLease|deliveryQueueKey|TransportAdapterBootstrap" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker -g "*.java"
```

## Suggested Implementation Order

1. JWR-0 inventory.
2. JWR-1 worker runtime definition contract.
3. JWR-2 dependency gate on
   `doc/archive/sdk/2026-06-17_EXTERNAL_WORKER_INVOCATION_PAYLOAD_BOUNDARY_CONVERGENCE_ROADMAP.md`.
4. JWR-3 explicit registration and evidence boundary.
5. JWR-4 common runtime core and protocol driver SPI.
6. JWR-5 worker runtime maintenance loop.
7. JWR-6 worker event reporter.
8. JWR-7 worker command runtime ability.
9. JWR-8 public API/adopter migration.
10. JWR-9 guards/docs/residue.

JWR-5 can start with heartbeat only. JWR-6 and JWR-7 can be implemented after
JWR-4 if reporting or command proof is too broad for the first runtime-core
slice. They must remain visible in this roadmap because maintenance, active
reporting, and commands are part of the worker ability model, not
protocol-local features.

## Roadmap Completion Criteria

This roadmap can be marked complete only when:

- Polling and WebSocket workers share one worker runtime definition model.
- Worker ability facts are not owned by protocol-specific builders/classes.
- The dedicated worker invocation payload boundary roadmap is complete or
  explicitly marked mainline-unblocked before this roadmap is archived.
- `DeliveryCommand` remains transport/adapter-owned and is not imported or
  exposed by Java external worker SDK runtime/handler contracts.
- Worker runtime start no longer hides registration or capability/state
  evidence reporting.
- Worker runtime maintenance loops are modeled as local upkeep and do not become
  platform task scheduling.
- Worker active reporting has an explicit owner and public shape.
- Worker command intake/ack is classified as a common runtime ability or a
  documented protocol limitation under the common model.
- Protocol drivers are internal network exchange implementations, not worker
  capability owners.
- Public docs and examples present polling and WebSocket as the same background
  worker runtime with different protocol drivers.
- Java SDK architecture guards prevent transport-internal identifiers from
  entering public worker runtime contracts.
- In-repo adopters compile against the final shape.
- Stale `WorkerSession` direction docs are archived or explicitly marked
  superseded.

## Open Decisions

- Final naming: `WorkerRuntimeDefinition` vs `WorkerDefinition`.
- Whether `WorkerRuntime` should expose `report(...)` directly or expose a
  separate `WorkerEventReporter`.
- Whether registration should use a new `WorkerRegistrationSpec` or narrow the
  existing `WorkerSpec` public name.
- Whether protocol-specific concrete classes remain public during convergence or
  become package-private driver implementations immediately after callers move.
- Whether WebSocket command intake exists in the current wire protocol or should
  be documented as a protocol limitation in JWR-7.
