# Java SDK Worker Session Model Convergence Roadmap

Status: complete; archived on 2026-06-17 after JWS-0 through JWS-6 landed.
Remaining report-capability API ownership is tracked separately by
`EXTERNAL_WORKER_CAPABILITY_EVIDENCE_API_CONVERGENCE_ROADMAP.md`.

## Summary

`sdk/xa-mass-java-sdk` already provides managed polling and WebSocket worker
sessions, but the public worker-session model is still implicit. The current
shape gives callers two concrete session classes without a shared worker
session contract, so repeated worker identity, handler runtime, listener, and
result handling semantics live in parallel implementations.

The goal of this roadmap is to add a narrow external Java SDK worker-session
model while keeping transport and worker-runtime ownership intact:

```text
WorkerSession = one external worker process/session lifecycle owned by the SDK
Worker = platform execution identity owned by worker-runtime/server
Adapter = transport mechanism owned by transport/adapters
```

This is an SDK ergonomics and boundary convergence roadmap. It does not change
worker-runtime lifecycle truth, transport endpoint evidence, task assignment,
or adapter contracts.

Worker sessions and adapters have similar action shapes: start, receive work,
send results, heartbeat or reconnect, and close. That similarity is not shared
ownership. `WorkerSession` is the external worker-process/session experience in
the Java SDK. Adapters remain transport-owned endpoint/session/final-hop
mechanics. Do not use this roadmap to create a shared worker/adapter lifecycle
base.

## Current Code Observations

- `MassPlatform.workerSessions()` is the stable external SDK factory for worker
  sessions.
- `WorkerSessions.polling()` returns `PollingWorkerSession.Builder`.
- `WorkerSessions.webSocket()` returns `WebSocketWorkerSession.Builder`.
- `PollingWorkerSession` and `WebSocketWorkerSession` both own:
  `workerId`, `workerGroupId`, worker attributes, `WorkerEventHandlers`,
  `WorkerEventHandlerRuntime`, `WorkerSessionListener`, `start()`,
  `isRunning()`, and `close()`.
- `PollingWorkerSession.start()` registers a polling worker, marks it online,
  currently reports capability/state, starts heartbeat, polls work, invokes
  handlers, and submits results through the worker API. The capability report is
  API debt, not a target worker-session requirement.
- `WebSocketWorkerSession.start()` registers a realtime worker, opens a
  WebSocket connection, receives pushed dispatch frames, invokes handlers, and
  queues result frames for submission over the socket.
- `WorkerEventHandlerRuntime` is already transport-neutral and is shared by
  both sessions, but the surrounding dispatch/result orchestration is still
  duplicated.
- `WorkerSessionListener` is already the shared lifecycle/failure callback
  surface for both sessions, but it is a broad union of polling-only and
  WebSocket-only failure callbacks. It is not proof that session lifecycle has
  a clean common event taxonomy.
- `WorkerSessionStartupStep` is also a union surface today: it includes polling
  startup steps such as `ONLINE`, `REPORT_CAPABILITY`, and `START_POLL`, plus
  WebSocket startup steps such as `CONNECT_WEBSOCKET` and
  `START_RESULT_SENDER`.
- `WorkerSpec` is the public registration DTO used by both sessions; it carries
  `workerId`, `workerGroupId`, `transportHint`, and attributes.
- WorkerGroup declaration remains an explicit topology/setup operation through
  `mass.workers()`. Managed worker sessions do not declare WorkerGroups.
- `xa-mass-java-sdk` production code must not depend on server, engine,
  embedded SDK, worker-runtime, transport runtime, or concrete transport
  adapter modules.
- JWS-1 removed the historical `xa-mass-transport-api` reactor dependency from
  `sdk/xa-mass-java-sdk`; main SDK source has no `com.xa.mass.transport`
  imports, and the architecture guard now forbids that dependency from
  returning.

Current representative source sites:

- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/MassPlatform.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session/WorkerSessions.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session/PollingWorkerSession.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session/WebSocketWorkerSession.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session/WorkerSessionListener.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/handler/WorkerEventHandlerRuntime.java`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerSpec.java`

## Owner Review

`xa-mass-java-sdk` owns the external caller worker-session experience:

```text
worker-session construction
worker registration request composition
worker event handler invocation
result publication through the chosen worker session mechanism
session lifecycle callbacks
polling/WebSocket reconnect or loop behavior
```

Worker-runtime/server own platform worker truth:

```text
WorkerGroup capability declaration
Worker execution identity
worker reachability/readiness/occupancy/admission evidence
worker command lifecycle
capability/state report application
```

Transport/adapters own protocol and endpoint mechanics:

```text
endpoint/session evidence
delivery feasibility
delivery command handoff
final-hop push or polling queue mechanics
result ingress relay
```

The Java SDK may expose a unified worker-session abstraction, but it must not
expose transport internals such as `adapterId`, `routeKey`, `connectionId`,
endpoint lease ids, delivery queue keys, transport runtime registries, or
embedded adapter bootstraps.

## Boundary Decision

Add a public `WorkerSession` contract for the common managed worker-session
lifecycle. Keep protocol-specific builders and behavior in concrete
implementations.

The first public contract should be intentionally small:

```java
public interface WorkerSession extends AutoCloseable {
    String workerId();
    String workerGroupId();
    String transportHint();
    WorkerSession start();
    boolean isRunning();
    @Override
    void close();
}
```

Concrete sessions may return covariant concrete types from `start()`:

```java
PollingWorkerSession start();
WebSocketWorkerSession start();
```

Do not add worker capability, state report, polling, reconnect, session token,
or queued-result methods to `WorkerSession`. Those remain concrete session or
policy details until a common lifecycle proves itself across transports.

`transportHint()` is the public worker registration hint from `WorkerSpec`, not
adapter identity or protocol identity. For the current concrete sessions:

```text
PollingWorkerSession.transportHint()   = "polling"
WebSocketWorkerSession.transportHint() = "realtime"
```

It must not return `websocket`, `socket`, `adapterId`, or any transport runtime
owner id.

## Target Shape

Target SDK model:

```text
WorkerSession
  minimal public lifecycle contract

WorkerSessionSpec
  optional shared immutable identity/options object:
  workerId, workerGroupId, attributes, WorkerEventHandlers, WorkerSessionListener
  `WorkerSessionListener` is the current broad event sink, not a clean common
  lifecycle taxonomy

PollingWorkerSession
  implements WorkerSession
  owns polling online/heartbeat/poll/HTTP result submit/offline behavior

WebSocketWorkerSession
  implements WorkerSession
  owns websocket connect/reconnect/frame read/result queue/close behavior

WorkerDispatchProcessor
  package-private shared handler invocation path
  already-decoded WorkerDispatchItem -> DispatchContext
    -> WorkerEventHandlerRuntime -> WorkerEventInvocation/WorkerResult

Session-owned result publication
  Polling keeps HTTP result submit and public WorkerResultSink behavior
  WebSocket keeps result frame encoding, queueing, reconnect, and close behavior
```

Allowed public factory shape:

```java
mass.workerSessions().polling()
mass.workerSessions().webSocket()

// Later, after WorkerSessionSpec lands:
mass.workerSessions().polling(spec)
mass.workerSessions().webSocket(spec).endpoint(...)
```

The existing concrete builders may stay as the ergonomic construction API, but
their common identity/handler/listener facts should converge through one shared
model instead of remaining separate copies.

## Non-Goals

- Do not change worker registration routes or public server worker API DTOs.
- Do not change task dispatch, result convergence, worker-runtime lifecycle, or
  transport delivery semantics.
- Do not add adapter registration or external adapter process APIs.
- Do not add `adapterId`, `adapterNodeId`, `routeKey`, `connectionId`, endpoint
  lease ids, delivery queue ids, or transport runtime types to Java SDK worker
  session contracts.
- Do not introduce a shared `RealtimeWorkerSession` abstraction before at
  least two realtime transports share a proven public lifecycle.
- Do not introduce an `AbstractWorkerSession` base class that hides protocol
  behavior in inheritance.
- Do not reuse `WorkerSession` as an adapter abstraction or introduce a shared
  base/interface between worker sessions and adapter runtime concepts such as
  `AdapterCommandExecutor`, `TransportAdapterBootstrap`, endpoint lease owners,
  or protocol session managers.
- Do not make `WorkerSession` own capability report semantics. Worker sessions
  should not declare or mutate capability truth.
- Do not make state/report startup behavior a common `WorkerSession` method in
  the first slice. State/readiness reporting remains a concrete-session/API
  review decision.
- Do not make worker sessions declare WorkerGroups. WorkerGroup declaration
  stays explicit through `mass.workers()`.

## Do Not Start With

Do not start by merging `PollingWorkerSession` and `WebSocketWorkerSession` into
one implementation or by adding a generic `pollLoop()` method to the public
contract.

Start with the minimal `WorkerSession` lifecycle contract, then extract shared
handler/result processing only where the current two implementations already
perform the same owner-owned operation.

## JWS-0 Inventory And Classification

Goal:

Classify the current Java SDK worker-session surface before changing API shape.

Scope:

- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/handler`
- `sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker`
- `sdk/xa-mass-java-sdk/src/test/java/com/xa/mass/client/worker/session`
- `integrations/xa-mass-scenario-launcher`
- `integrations/xa-mass-worker-pack`
- `sdk/xa-mass-java-sdk/README.md`
- `sdk/xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md`
- `xa-mass-server/src/main/java/com/xa/mass/api/internal/ExternalWorkerApiController.java`
- `xa-mass-server/src/test/java`
- `xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/WorkerCapabilityAuthority.java`
- `xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/WorkerReportOwner.java`
- `xa-mass-worker-runtime/src/test/java`

Classification:

| Surface | Current owner | Target |
| --- | --- | --- |
| `WorkerSessions` | Java SDK session factory | stable session factory |
| `PollingWorkerSession` | polling session implementation | concrete `WorkerSession` |
| `WebSocketWorkerSession` | WebSocket session implementation | concrete `WorkerSession` |
| `WorkerSessionListener` | shared callback surface | keep shared |
| `WorkerSessionStartupStep` | shared startup failure enum with protocol-specific values | classify as union surface before adding more common lifecycle policy |
| `WorkerEventHandlers` | handler registry | keep shared |
| `WorkerEventHandlerRuntime` | handler invocation | keep shared |
| `WorkerResultSink` | polling public result sink hook | keep polling-specific in the first slice |
| `WorkerSpec` | worker registration DTO | keep registration DTO, not session lifecycle contract |
| `xa-mass-transport-api` dependency | reactor POM residue, no main-source transport imports | remove from Java SDK production dependencies |
| `WorkerClient.reportCapability(...)` | public worker API client method for current server route | classify as API debt / current worker-local evidence path, not `WorkerSession` policy |
| `ExternalWorkerApiController :report-capability` | server external worker API surface | review as owner boundary; remove, rename, or narrow before promoting any SDK policy |
| `WorkerCapabilityAuthority` / `WorkerReportOwner` | worker-runtime projection of a worker-originated report slice | classify separately from WorkerGroup capability declaration |
| `WorkerScenarioRegistrar.markApiOnline(...)` | production adopter that currently calls `reportCapability` | classify/migrate after the API owner decision; do not preserve blindly |
| `EXTERNAL_SDK_QUICKSTART.md` report-capability wording | public docs currently presenting the route as stable worker protocol | revise when API decision lands; do not describe it as WorkerSession capability truth |
| scenario launcher sessions | SDK adopter | migrate to common `WorkerSession` only where concrete methods are not needed |
| worker-pack helpers | SDK adopter | migrate cautiously; keep concrete returns when callers need polling-specific APIs |

Acceptance:

- Inventory distinguishes public API, package-private implementation, tests,
  and integration adopters.
- Inventory names every production caller that directly uses
  `PollingWorkerSession` or `WebSocketWorkerSession`.
- Inventory confirms whether any caller needs concrete-only methods such as
  `sessionToken()` or `pendingResults()`.
- Inventory records that `WorkerSessionListener` and `WorkerSessionStartupStep`
  are current union surfaces, not clean proof of shared lifecycle semantics.
- Inventory checks Java SDK main-source imports and POM dependencies; the
  current unused `xa-mass-transport-api` dependency must be removed or
  explicitly classified as a blocking dependency residue.
- Inventory names every production and test caller of `reportCapability`,
  `reportState`, `WorkerCapabilityReport`, and `availableEventCodes`, separated
  by Java SDK, server, worker-runtime, integration, and docs.
- Inventory distinguishes WorkerGroup capability declaration from worker-local
  handler availability, scheduling attributes, and bounded state/readiness
  evidence.
- No implementation behavior changes in this slice.

Verification candidates:

```bash
rg -n "PollingWorkerSession|WebSocketWorkerSession|WorkerSessions|WorkerSessionListener|WorkerResultSink" sdk/xa-mass-java-sdk/src/main/java sdk/xa-mass-java-sdk/src/test/java integrations -g "*.java" -g "*.md"
rg -n "import com\\.xa\\.mass\\.transport|xa-mass-transport-api" sdk/xa-mass-java-sdk/src/main/java sdk/xa-mass-java-sdk/pom.xml sdk/xa-mass-java-sdk/pom.consumer.xml -g "*.java" -g "*.xml"
rg -n "reportCapability|report-capability|WorkerCapabilityReport|availableEventCodes" sdk xa-mass-server xa-mass-worker-runtime integrations -g "*.java" -g "*.md"
```

## JWS-1 Minimal WorkerSession Contract

Goal:

Introduce a narrow common worker-session lifecycle contract without changing
protocol behavior.

Scope:

- Add `com.xa.mass.client.worker.session.WorkerSession`.
- Make `PollingWorkerSession` implement `WorkerSession`.
- Make `WebSocketWorkerSession` implement `WorkerSession`.
- Add missing `workerId()`, `workerGroupId()`, and `transportHint()` methods
  to concrete sessions where needed.
- Remove the unused `xa-mass-transport-api` dependency from
  `sdk/xa-mass-java-sdk/pom.xml` unless inventory discovers a real main-source
  transport contract usage.
- Extend the Java SDK architecture guard so the external Java SDK cannot depend
  on `xa-mass-transport-api` or import `com.xa.mass.transport.*` production
  types.
- Keep concrete `start()` return types through covariant returns.
- Do not remove existing concrete builders.

Acceptance:

- Both managed session classes implement `WorkerSession`.
- `WorkerSession` exposes only identity, transport hint, `start()`,
  `isRunning()`, and `close()`.
- `transportHint()` returns only the public worker registration hint used in
  `WorkerSpec`: polling sessions return `polling`, WebSocket sessions return
  `realtime`.
- `transportHint()` does not expose adapter id, protocol id, route key,
  endpoint id, or transport runtime owner id.
- `WorkerSession` does not expose poll, heartbeat, reconnect, endpoint,
  session-token, result-queue, adapter, route, or command methods.
- `sdk/xa-mass-java-sdk` no longer depends on `xa-mass-transport-api` unless
  this slice stops and records a concrete source-level reason.
- Existing polling and WebSocket session tests continue to pass.
- Existing callers using concrete session types still compile.

Verification candidates:

```bash
./mvnw -q -pl sdk/xa-mass-java-sdk -am test -Dtest=JavaExternalSdkArchitectureGuardTest,PollingWorkerSessionTest,WebSocketWorkerSessionTest,WorkerEventHandlerRuntimeTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -q -pl integrations/xa-mass-scenario-launcher,integrations/xa-mass-worker-pack -am -DskipTests compile
```

## JWS-2 Shared Session Spec

Goal:

Move common worker identity and handler/listener configuration into one shared
SDK-owned model, without hiding transport-specific options.

Target:

```java
public final class WorkerSessionSpec {
    String workerId;
    String workerGroupId;
    Map<String, String> attributes;
    WorkerEventHandlers eventHandlers;
    WorkerSessionListener listener;
}
```

Scope:

- Add immutable `WorkerSessionSpec` with a builder.
- Add `WorkerSessions.polling(WorkerSessionSpec spec)`.
- Add `WorkerSessions.webSocket(WorkerSessionSpec spec)`.
- Keep protocol-specific options on concrete builders:
  polling intervals, heartbeat interval, max messages, poll timeout,
  WebSocket endpoint, reconnect settings, outbound result queue capacity,
  object mapper, HTTP client, and connector.
- Keep direct concrete builder methods for ergonomics unless the inventory
  proves they should be replaced in one slice.

Acceptance:

- Shared session facts are modeled once in `WorkerSessionSpec`.
- `WorkerSessionSpec` has no transport internals:
  `adapterId`, `adapterNodeId`, `routeKey`, `connectionId`, endpoint lease ids,
  delivery queue keys, or transport runtime types.
- `WorkerSessionSpec` does not declare WorkerGroups and does not own
  capability/state report policy.
- If `WorkerSessionSpec` carries `WorkerSessionListener`, the roadmap or code
  documents that this listener is the existing broad session event sink and not
  a clean common lifecycle taxonomy.
- Both concrete builder paths can be built from a `WorkerSessionSpec`.
- Existing examples can be migrated gradually without changing wire behavior.

Verification candidates:

```bash
./mvnw -q -pl sdk/xa-mass-java-sdk -am test -Dtest=PollingWorkerSessionTest,WebSocketWorkerSessionTest,WorkerClientTest -Dsurefire.failIfNoSpecifiedTests=false
```

## JWS-3 Shared Dispatch Processing

Goal:

Remove duplicated handler invocation/result wiring only if it creates a real
owner-owned processing unit. Do not add a processor that only forwards to
`WorkerEventHandlerRuntime.invoke(...)`.

Target:

```text
WorkerDispatchProcessor
  already-decoded WorkerDispatchItem -> DispatchContext
    -> WorkerEventHandlerRuntime -> WorkerEventInvocation/WorkerResult

Session-owned result publication
  Polling: WorkerResultSink or HTTP worker API submit
  WebSocket: result frame encode, outbound queue, reconnect/abandon handling
```

Scope:

- Add package-private `WorkerDispatchProcessor` only for already-decoded
  `WorkerDispatchItem` handler invocation, and only if it owns the full
  "decoded item -> dispatch context -> invocation result -> handler failure
  notification" unit.
- Do not add `WorkerDispatchProcessor` if the implementation would merely wrap
  the existing two or three lines around `WorkerEventHandlerRuntime.invoke(...)`;
  in that case document the rejection and keep the duplicate local code.
- Use the processor from `PollingWorkerSession.handleItem(...)`.
- Use the processor from `WebSocketWorkerSession.handleFrame(...)` after frame
  decoding.
- Keep frame decoding in WebSocket session.
- Keep HTTP poll/result behavior and the public `WorkerResultSink` hook in
  Polling session.
- Keep result frame encoding, outbound result queueing, reconnect behavior, and
  queued-result abandonment in WebSocket session.
- Do not add a shared result publisher in the first slice unless the inventory
  proves it is package-private, does not replace `WorkerResultSink`, and does
  not own WebSocket queue/reconnect behavior.

Acceptance:

- Handler invocation logic exists in one owner-owned SDK implementation, or the
  roadmap records that the current duplicated code is intentionally kept because
  a processor would be a pass-through wrapper.
- Polling and WebSocket sessions still publish results through their own
  concrete mechanisms.
- `WorkerDispatchProcessor` has no dependency on `WorkerClient`, HTTP submit,
  WebSocket frames, result queues, reconnect policy, or `WorkerResultSink`.
- `WorkerDispatchProcessor`, if created, owns handler failure notification
  semantics; otherwise it is not added.
- The polling custom `WorkerResultSink` extension path remains covered.
- WebSocket queued-result and reconnect/abandon behavior remains covered.
- Handler failure callbacks remain equivalent for both transports.
- WebSocket frame/protocol failure handling remains WebSocket-specific.
- Polling poll/heartbeat failures remain polling-specific.

Verification candidates:

```bash
./mvnw -q -pl sdk/xa-mass-java-sdk -am test -Dtest=WorkerEventHandlerRuntimeTest,PollingWorkerSessionTest,WebSocketWorkerSessionTest -Dsurefire.failIfNoSpecifiedTests=false
```

## JWS-4 Startup Policy Classification

Goal:

Classify startup-time worker reports and explicitly challenge the existing
worker-side capability report API before introducing any shared policy.

Current difference:

- `PollingWorkerSession.start()` reports capability and state after online.
- `WebSocketWorkerSession.start()` registers and connects, but does not call
  polling-only online/heartbeat/offline APIs and does not currently report
  capability/state.

Decision:

WorkerGroup capability declaration remains server/worker-runtime truth through
explicit `mass.workers().declareGroup(...)` calls. Worker sessions may only
provide worker-local evidence such as handler availability, scheduling
attributes, readiness, and bounded state. `WorkerEventHandlers.eventCodes()`
must not become WorkerGroup capability declaration and must not expand the
worker group event universe.

`reportCapability` is not a target requirement for worker sessions. In the
current API it is a suspicious mixed model: it accepts worker-local handler
availability (`availableEventCodes`) and scheduling attributes, but its name and
placement imply mutable worker capability truth. Its owner decision has moved
to
[`EXTERNAL_WORKER_CAPABILITY_EVIDENCE_API_CONVERGENCE_ROADMAP.md`](./EXTERNAL_WORKER_CAPABILITY_EVIDENCE_API_CONVERGENCE_ROADMAP.md);
this roadmap only proves it is not part of `WorkerSession`.

Current public report-capability/report-state routes are polling-worker routes.
Do not add a shared startup policy that calls `reportCapability`. Do not call
polling-only report routes for WebSocket/realtime sessions unless a separate
server/worker-runtime contract changes that.

State/readiness reporting may remain useful worker evidence, but it must not be
a method on `WorkerSession` until the desired public semantics are proven for
polling and realtime sessions.

Scope:

- Inventory current capability/state report behavior.
- Inventory current `WorkerSessionStartupStep` usage and decide whether it
  remains a broad union enum or should later split into common lifecycle steps
  plus protocol-specific startup evidence.
- Review `ExternalWorkerApiController` worker-report endpoints as API surface,
  especially whether `/workers/{workerId}:report-capability` should be removed
  or renamed to a narrower worker-local evidence concept.
- Review `WorkerCapabilityAuthority` / `WorkerReportOwner` to decide whether
  the current report projection is still needed after WorkerGroup declaration
  owns event capability truth.
- Review `WorkerScenarioRegistrar.markApiOnline(...)` and Java SDK quickstart
  wording so current `reportCapability` calls are not preserved as a hidden
  WorkerSession startup requirement.
- Record that current server report-capability/report-state endpoints are
  guarded as polling-worker operations.
- Decide whether to add an optional `WorkerSessionStartupPolicy` or keep
  state/readiness reporting as concrete-session behavior.
- If a policy is added, make it explicit and opt-in/opt-out, apply it only to
  protocols that support the server contract, and do not include
  `reportCapability`.

External worker API review lens:

| Endpoint area | Current role | Target judgment |
| --- | --- | --- |
| `POST /worker-groups` | WorkerGroup capability declaration | keep as capability truth owner |
| `POST /workers` | worker identity, group membership, attributes, transport hint | keep as worker registration owner |
| `:report-capability` | mixes handler availability, scheduling attrs, and capability wording | do not promote; remove, rename, or narrow before any shared SDK policy |
| `:report-state` | bounded worker state/readiness evidence | may remain, but do not add to `WorkerSession` until realtime semantics are explicit |
| polling `online/heartbeat/offline/poll/submitResult` | polling protocol/session behavior | keep protocol-specific; do not apply to WebSocket |

Acceptance:

- The roadmap or implementation records why polling and WebSocket startup
  report behavior differs.
- `WorkerSession` remains free of report-capability/report-state methods.
- `WorkerSessionSpec` remains free of capability declaration fields such as
  `availableEventCodes`.
- `WorkerSessionStartupStep` is not expanded as a fake common lifecycle enum;
  protocol-specific startup steps remain classified as protocol-specific until
  a follow-up taxonomy proves a better shape.
- Shared policy, if any, cannot call `reportCapability`, use handler event
  codes as WorkerGroup declaration, or expand WorkerGroup capability truth.
- `ExternalWorkerApiController` report API review is a required follow-up before
  any new SDK startup policy. The review must separate WorkerGroup capability
  declaration, worker registration, worker-local handler availability, and
  bounded worker state/readiness evidence.
- `reportCapability` owner decision is tracked by
  `EXTERNAL_WORKER_CAPABILITY_EVIDENCE_API_CONVERGENCE_ROADMAP.md`; it is not
  treated as `WorkerSession` capability truth or shared startup policy in this
  roadmap.
- WebSocket startup remains free of online/heartbeat/offline/report-capability/
  report-state HTTP calls under the current server contract.
- Any new shared policy is SDK-owned and uses `WorkerClient` public routes, not
  embedded/runtime internals.

Verification candidates:

```bash
rg -n "reportCapability|reportState|WorkerCapabilityReport|WorkerStateReport" sdk/xa-mass-java-sdk/src/main/java sdk/xa-mass-java-sdk/src/test/java
rg -n "WorkerSessionStartupStep" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session sdk/xa-mass-java-sdk/src/test/java -g "*.java"
rg -n "requirePollingWorker|reportWorkerCapability|reportWorkerState" xa-mass-server/src/main/java/com/xa/mass/api/internal/ExternalWorkerApiController.java xa-mass-server/src/test/java
rg -n "WorkerCapabilityAuthority|WorkerReportOwner|applyWorkerCapabilityReport" xa-mass-worker-runtime/src/main/java xa-mass-worker-runtime/src/test/java -g "*.java"
```

## JWS-5 Adopter Migration

Goal:

Use the common worker-session model in SDK adopters where it improves clarity
without hiding concrete behavior.

Scope:

- `integrations/xa-mass-scenario-launcher`
- `integrations/xa-mass-worker-pack`
- `sdk/xa-mass-java-sdk/README.md`
- `sdk/xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md`
- `integrations/README.md` if documented caller behavior changes.
- Public docs and integration code that currently mention or call
  `reportCapability`, including `WorkerScenarioRegistrar`.

Acceptance:

- Scenario launcher may store and close sessions through `WorkerSession` when
  it does not need concrete-only methods.
- Worker-pack helpers may keep concrete return types when their public helper
  names are polling-specific, such as `startPolling()`.
- Docs present `WorkerSession` as the shared managed-session lifecycle, while
  keeping polling and WebSocket concrete examples.
- Docs and adopters do not present `reportCapability` as WorkerGroup capability
  truth or as a `WorkerSession` requirement. Existing uses are either migrated
  after the API decision or explicitly described as current worker-local
  evidence behavior.
- No integration path imports embedded SDK, worker runtime, transport runtime,
  or concrete transport adapter modules.

Verification candidates:

```bash
./mvnw -q -pl integrations/xa-mass-scenario-launcher,integrations/xa-mass-worker-pack -am -DskipTests compile
./mvnw -q -pl sdk/xa-mass-java-sdk -am test
rg -n "reportCapability|report-capability|availableEventCodes|WorkerCapabilityReport" integrations sdk/xa-mass-java-sdk/README.md sdk/xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md -g "*.java" -g "*.md"
```

## JWS-6 Guards And Residue

Goal:

Prevent the new worker-session abstraction from becoming a transport or
worker-runtime owner.

Scope:

- Add or extend Java SDK architecture tests.
- Update `sdk/xa-mass-java-sdk/README.md`.
- Update `doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md` only if a new dependency or
  public ownership rule is introduced.

Guard candidates:

- `WorkerSession` method allowlist:
  `workerId`, `workerGroupId`, `transportHint`, `start`, `isRunning`, `close`.
- `WorkerSessionSpec` field allowlist:
  `workerId`, `workerGroupId`, `attributes`, `eventHandlers`, `listener`.
- `xa-mass-java-sdk` production source must not import:
  `xa-mass-server`, `xa-mass-engine`, `xa-mass-embedded-sdk`,
  `xa-mass-worker-runtime`, `transport_api`, `transport_runtime`,
  `websocket-adapter`, `socket-adapter`, or `polling-adapter`.
- `sdk/xa-mass-java-sdk/pom.xml` and `pom.consumer.xml` must not depend on
  `xa-mass-transport-api`, `xa-mass-transport-runtime`, or concrete transport
  adapter artifacts.
- Worker-session package must not contain:
  `adapterId`, `adapterNodeId`, `routeKey`, `connectionId`, `endpointLease`,
  `deliveryQueueKey`, or `TransportAdapterBootstrap` in public session
  contracts.

Acceptance:

- A focused guard fails if `WorkerSession` grows transport or
  worker-runtime-owned methods.
- A focused guard fails if `WorkerSessionSpec` carries transport internals.
- `JavaExternalSdkArchitectureGuardTest` fails if Java SDK production code
  imports any `com.xa.mass.transport.*` package or if either Java SDK POM
  declares `xa-mass-transport-api`.
- Docs describe `WorkerSession` as SDK managed worker-process/session
  lifecycle, not platform worker truth.
- Residue scan shows examples and tests do not preserve a second competing
  worker-session abstraction.

Verification candidates:

```bash
./mvnw -q -pl sdk/xa-mass-java-sdk -am test -Dtest=JavaExternalSdkArchitectureGuardTest,PollingWorkerSessionTest,WebSocketWorkerSessionTest -Dsurefire.failIfNoSpecifiedTests=false
rg -n "adapterId|adapterNodeId|routeKey|connectionId|endpointLease|deliveryQueueKey|TransportAdapterBootstrap" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session/WorkerSession.java sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session/WorkerSessionSpec.java
rg -n "import com\\.xa\\.mass\\.transport|xa-mass-transport-api" sdk/xa-mass-java-sdk/src/main/java sdk/xa-mass-java-sdk/pom.xml sdk/xa-mass-java-sdk/pom.consumer.xml -g "*.java" -g "*.xml"
```

## Suggested Implementation Order

1. JWS-0 inventory.
2. JWS-1 minimal `WorkerSession` contract.
3. JWS-2 shared `WorkerSessionSpec`.
4. JWS-3 shared dispatch processor only if it is not a pass-through wrapper.
5. JWS-4 startup policy classification.
6. JWS-5 adopter/doc migration.
7. JWS-6 guards and residue scan.

JWS-3 is optional. If the implementation shows that a dispatch processor only
wraps `WorkerEventHandlerRuntime.invoke(...)`, skip it and record the
code-grounded rejection. Do not combine JWS-4 with JWS-1; capability/state
report behavior is a semantic decision, not required for the minimal
worker-session contract.

## Roadmap Completion Criteria

This roadmap can be marked complete only when:

- `PollingWorkerSession` and `WebSocketWorkerSession` implement the common
  `WorkerSession` contract.
- The common contract remains a narrow SDK lifecycle contract and does not
  expose transport internals, worker-runtime mutation, or server DTOs.
- Common worker identity/handler/listener facts are modeled once or the
  roadmap records why a shared spec was intentionally rejected.
- Handler invocation duplication is removed or a code-grounded exception is
  documented.
- `transportHint()` remains public worker registration vocabulary and never
  becomes adapter/protocol identity.
- The Java SDK has no production dependency on transport API/runtime or
  concrete transport adapter artifacts.
- `WorkerSessionListener` and `WorkerSessionStartupStep` are either narrowed or
  explicitly documented as broad union surfaces that are not proof of clean
  common lifecycle semantics.
- Capability/state report startup behavior is explicitly classified and no
  longer appears as accidental polling-only drift.
- `reportCapability` is moved to the linked External Worker Capability
  Evidence API roadmap and is not treated as WorkerSession capability truth,
  shared startup policy, or unresolved completion residue here.
- Public SDK docs and integration adopters no longer describe
  `reportCapability` as WorkerGroup capability truth or as required
  `WorkerSession` behavior.
- `WorkerSessions` remains the stable factory and docs show both concrete
  session examples plus the common lifecycle model.
- Scenario launcher and worker-pack compile against the final public shape.
- Java SDK dependency and source guards confirm no drift into embedded runtime,
  worker-runtime, server, engine, or concrete transport adapter modules.

## Verification Set

Focused SDK proof:

```bash
./mvnw -q -pl sdk/xa-mass-java-sdk -am test -Dtest=WorkerClientTest,WorkerEventHandlerRuntimeTest,PollingWorkerSessionTest,WebSocketWorkerSessionTest -Dsurefire.failIfNoSpecifiedTests=false
```

SDK dependency proof:

```bash
./mvnw -q -pl sdk/xa-mass-java-sdk -am dependency:tree
./mvnw -f sdk/xa-mass-java-sdk/pom.consumer.xml -DskipTests package
rg -n "xa-mass-transport-api|xa-mass-transport-runtime|xa-mass-transport-websocket|xa-mass-transport-socket|xa-mass-transport-polling" sdk/xa-mass-java-sdk/pom.xml sdk/xa-mass-java-sdk/pom.consumer.xml
```

Adopter compile proof:

```bash
./mvnw -q -pl integrations/xa-mass-scenario-launcher,integrations/xa-mass-worker-pack -am -DskipTests compile
```

Server public-worker proof:

```bash
./mvnw -q -pl xa-mass-server -am test -Dtest=JavaExternalSdkPollingSessionIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -q -pl xa-mass-server -am test -Dtest=ExternalWorkerRealtimeRegistrationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

The realtime server proof validates the realtime registration boundary and
current no-polling-lifecycle behavior. It is not a full WebSocket worker-session
dispatch/result E2E replacement.

Boundary source scan:

```bash
rg -n "import com\\.xa\\.mass\\.(server|engine|starter|worker\\.runtime)|transport\\.runtime|websocket\\.runtime|socket\\.runtime|polling\\.runtime" sdk/xa-mass-java-sdk/src/main/java
rg -n "import com\\.xa\\.mass\\.transport\\." sdk/xa-mass-java-sdk/src/main/java
rg -n "adapterId|adapterNodeId|routeKey|connectionId|endpointLease|deliveryQueueKey|TransportAdapterBootstrap" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session/WorkerSession.java sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session/WorkerSessionSpec.java
rg -n "reportCapability|report-capability|availableEventCodes|WorkerCapabilityReport" integrations sdk/xa-mass-java-sdk/README.md sdk/xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md -g "*.java" -g "*.md"
```

## Final Disposition

- `WorkerSessionSpec` landed as a public Java SDK construction helper.
- `WorkerDispatchProcessor` landed as a package-private handler invocation
  helper because it removes real polling/WebSocket duplication and injects the
  session-owned worker identity fallback for pulled dispatch items.
- `WorkerResultSink` remains polling-specific and WebSocket result queueing
  remains protocol-owned.
- `reportCapability` removal/rename/narrowing is owned by
  `EXTERNAL_WORKER_CAPABILITY_EVIDENCE_API_CONVERGENCE_ROADMAP.md`, not by the
  WorkerSession model roadmap.
- State/readiness reporting remains outside the common `WorkerSession`
  lifecycle contract.
- `WorkerSessionListener` and `WorkerSessionStartupStep` remain documented broad
  union surfaces; splitting them later is not a prerequisite for this roadmap.
- Concrete session builders keep current common setters and also accept
  `WorkerSessionSpec`.
