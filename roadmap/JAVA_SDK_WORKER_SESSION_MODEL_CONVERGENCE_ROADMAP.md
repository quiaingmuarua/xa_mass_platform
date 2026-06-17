# Java SDK Worker Session Model Convergence Roadmap

Status: proposed direction document.

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
  reports capability/state, starts heartbeat, polls work, invokes handlers, and
  submits results through the worker API.
- `WebSocketWorkerSession.start()` registers a realtime worker, opens a
  WebSocket connection, receives pushed dispatch frames, invokes handlers, and
  queues result frames for submission over the socket.
- `WorkerEventHandlerRuntime` is already transport-neutral and is shared by
  both sessions, but the surrounding dispatch/result orchestration is still
  duplicated.
- `WorkerSessionListener` is already the shared lifecycle/failure callback
  surface for both sessions.
- `WorkerSpec` is the public registration DTO used by both sessions; it carries
  `workerId`, `workerGroupId`, `transportHint`, and attributes.
- WorkerGroup declaration remains an explicit topology/setup operation through
  `mass.workers()`. Managed worker sessions do not declare WorkerGroups.
- `xa-mass-java-sdk` production code must not depend on server, engine,
  embedded SDK, worker-runtime, transport runtime, or concrete transport
  adapter modules.

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

## Target Shape

Target SDK model:

```text
WorkerSession
  minimal public lifecycle contract

WorkerSessionSpec
  optional shared immutable identity/options object:
  workerId, workerGroupId, attributes, WorkerEventHandlers, WorkerSessionListener

PollingWorkerSession
  implements WorkerSession
  owns polling online/heartbeat/poll/HTTP result submit/offline behavior

WebSocketWorkerSession
  implements WorkerSession
  owns websocket connect/reconnect/frame read/result queue/close behavior

WorkerDispatchProcessor
  package-private shared handler invocation path
  WorkerDispatchItem -> DispatchContext -> WorkerEventHandlerRuntime -> WorkerResult

WorkerResultPublisher
  package-private session-owned result sink abstraction
  concrete sessions publish through HTTP worker API or websocket frame queue
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
- Do not make `WorkerSession` own capability/state report semantics in the
  first slice. Capability/state report startup policy remains a later decision.
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

Classification:

| Surface | Current owner | Target |
| --- | --- | --- |
| `WorkerSessions` | Java SDK session factory | stable session factory |
| `PollingWorkerSession` | polling session implementation | concrete `WorkerSession` |
| `WebSocketWorkerSession` | WebSocket session implementation | concrete `WorkerSession` |
| `WorkerSessionListener` | shared callback surface | keep shared |
| `WorkerEventHandlers` | handler registry | keep shared |
| `WorkerEventHandlerRuntime` | handler invocation | keep shared |
| `WorkerResultSink` | polling result sink hook | classify as polling-specific or shared publisher |
| `WorkerSpec` | worker registration DTO | keep registration DTO, not session lifecycle contract |
| scenario launcher sessions | SDK adopter | migrate to common `WorkerSession` only where concrete methods are not needed |
| worker-pack helpers | SDK adopter | migrate cautiously; keep concrete returns when callers need polling-specific APIs |

Acceptance:

- Inventory distinguishes public API, package-private implementation, tests,
  and integration adopters.
- Inventory names every production caller that directly uses
  `PollingWorkerSession` or `WebSocketWorkerSession`.
- Inventory confirms whether any caller needs concrete-only methods such as
  `sessionToken()` or `pendingResults()`.
- No implementation behavior changes in this slice.

Verification candidates:

```bash
rg -n "PollingWorkerSession|WebSocketWorkerSession|WorkerSessions|WorkerSessionListener|WorkerResultSink" sdk/xa-mass-java-sdk/src/main/java sdk/xa-mass-java-sdk/src/test/java integrations -g "*.java" -g "*.md"
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
- Keep concrete `start()` return types through covariant returns.
- Do not remove existing concrete builders.

Acceptance:

- Both managed session classes implement `WorkerSession`.
- `WorkerSession` exposes only identity, transport hint, `start()`,
  `isRunning()`, and `close()`.
- `WorkerSession` does not expose poll, heartbeat, reconnect, endpoint,
  session-token, result-queue, adapter, route, or command methods.
- Existing polling and WebSocket session tests continue to pass.
- Existing callers using concrete session types still compile.

Verification candidates:

```bash
./mvnw -q -pl sdk/xa-mass-java-sdk -am test -Dtest=PollingWorkerSessionTest,WebSocketWorkerSessionTest,WorkerEventHandlerRuntimeTest -Dsurefire.failIfNoSpecifiedTests=false
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
- Both concrete builder paths can be built from a `WorkerSessionSpec`.
- Existing examples can be migrated gradually without changing wire behavior.

Verification candidates:

```bash
./mvnw -q -pl sdk/xa-mass-java-sdk -am test -Dtest=PollingWorkerSessionTest,WebSocketWorkerSessionTest,WorkerClientTest -Dsurefire.failIfNoSpecifiedTests=false
```

## JWS-3 Shared Dispatch Processing

Goal:

Remove duplicated handler invocation/result wiring while keeping session-owned
result publication separate.

Target:

```text
WorkerDispatchProcessor
  WorkerDispatchItem -> DispatchContext -> WorkerEventHandlerRuntime -> WorkerResult

WorkerResultPublisher
  publish(DispatchContext, WorkerResult)
```

Scope:

- Add package-private `WorkerDispatchProcessor`.
- Add package-private `WorkerResultPublisher` or equivalent if it materially
  reduces duplication.
- Use the processor from `PollingWorkerSession.handleItem(...)`.
- Use the processor from `WebSocketWorkerSession.handleFrame(...)` after frame
  decoding.
- Keep frame decoding in WebSocket session.
- Keep HTTP poll/result behavior in Polling session.
- Keep result frame queueing/reconnect behavior in WebSocket session.

Acceptance:

- Handler invocation logic exists in one owner-owned SDK implementation.
- Polling and WebSocket sessions still publish results through their own
  concrete mechanisms.
- Handler failure callbacks remain equivalent for both transports.
- WebSocket frame/protocol failure handling remains WebSocket-specific.
- Polling poll/heartbeat failures remain polling-specific.

Verification candidates:

```bash
./mvnw -q -pl sdk/xa-mass-java-sdk -am test -Dtest=WorkerEventHandlerRuntimeTest,PollingWorkerSessionTest,WebSocketWorkerSessionTest -Dsurefire.failIfNoSpecifiedTests=false
```

## JWS-4 Startup Policy Classification

Goal:

Classify, but do not prematurely unify, startup-time capability/state reporting.

Current difference:

- `PollingWorkerSession.start()` reports capability and state after online.
- `WebSocketWorkerSession.start()` registers and connects, but does not call
  polling-only online/heartbeat/offline APIs and does not currently report
  capability/state.

Decision:

Capability/state reporting is worker-owned behavior, not transport-owned
behavior. It may become a shared SDK startup policy later, but it must not be a
method on `WorkerSession` until the desired public semantics are proven for
polling and realtime sessions.

Scope:

- Inventory current capability/state report behavior.
- Decide whether to add an optional `WorkerSessionStartupPolicy` or keep
  reporting as concrete-session behavior.
- If a policy is added, make it explicit and opt-in/opt-out; do not silently
  change WebSocket startup behavior.

Acceptance:

- The roadmap or implementation records why polling and WebSocket startup
  report behavior differs.
- `WorkerSession` remains free of report-capability/report-state methods.
- Any new shared policy is SDK-owned and uses `WorkerClient` public routes, not
  embedded/runtime internals.

Verification candidates:

```bash
rg -n "reportCapability|reportState|WorkerCapabilityReport|WorkerStateReport" sdk/xa-mass-java-sdk/src/main/java sdk/xa-mass-java-sdk/src/test/java
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

Acceptance:

- Scenario launcher may store and close sessions through `WorkerSession` when
  it does not need concrete-only methods.
- Worker-pack helpers may keep concrete return types when their public helper
  names are polling-specific, such as `startPolling()`.
- Docs present `WorkerSession` as the shared managed-session lifecycle, while
  keeping polling and WebSocket concrete examples.
- No integration path imports embedded SDK, worker runtime, transport runtime,
  or concrete transport adapter modules.

Verification candidates:

```bash
./mvnw -q -pl integrations/xa-mass-scenario-launcher,integrations/xa-mass-worker-pack -am test -DskipTests
./mvnw -q -pl sdk/xa-mass-java-sdk -am test
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
  `xa-mass-worker-runtime`, `transport_runtime`, `websocket-adapter`,
  `socket-adapter`, or `polling-adapter`.
- Worker-session package must not contain:
  `adapterId`, `adapterNodeId`, `routeKey`, `connectionId`, `endpointLease`,
  `deliveryQueueKey`, or `TransportAdapterBootstrap` in public session
  contracts.

Acceptance:

- A focused guard fails if `WorkerSession` grows transport or
  worker-runtime-owned methods.
- A focused guard fails if `WorkerSessionSpec` carries transport internals.
- Docs describe `WorkerSession` as SDK managed worker-process/session
  lifecycle, not platform worker truth.
- Residue scan shows examples and tests do not preserve a second competing
  worker-session abstraction.

Verification candidates:

```bash
./mvnw -q -pl sdk/xa-mass-java-sdk -am test -Dtest=JavaSdkWorkerSessionArchitectureGuardTest,PollingWorkerSessionTest,WebSocketWorkerSessionTest -Dsurefire.failIfNoSpecifiedTests=false
rg -n "adapterId|adapterNodeId|routeKey|connectionId|endpointLease|deliveryQueueKey|TransportAdapterBootstrap" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session -g "*.java"
```

## Suggested Implementation Order

1. JWS-0 inventory.
2. JWS-1 minimal `WorkerSession` contract.
3. JWS-3 shared dispatch processor, if the inventory confirms no behavioral
   divergence is hidden in the duplicated code.
4. JWS-2 shared `WorkerSessionSpec`.
5. JWS-4 startup policy classification.
6. JWS-5 adopter/doc migration.
7. JWS-6 guards and residue scan.

JWS-2 and JWS-3 can swap order if implementation shows the shared spec is the
cleaner first compile point. Do not combine JWS-4 with JWS-1; capability/state
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
- Capability/state report startup behavior is explicitly classified and no
  longer appears as accidental polling-only drift.
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
```

Adopter compile proof:

```bash
./mvnw -q -pl integrations/xa-mass-scenario-launcher,integrations/xa-mass-worker-pack -am -DskipTests compile
```

Boundary source scan:

```bash
rg -n "import com\\.xa\\.mass\\.(server|engine|starter|worker\\.runtime)|transport\\.runtime|websocket\\.runtime|socket\\.runtime|polling\\.runtime" sdk/xa-mass-java-sdk/src/main/java
rg -n "adapterId|adapterNodeId|routeKey|connectionId|endpointLease|deliveryQueueKey|TransportAdapterBootstrap" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session -g "*.java"
```

## Open Decisions

- Whether `WorkerSessionSpec` should be public in the first implementation
  slice or introduced after the minimal interface lands.
- Whether `WorkerResultSink` should remain polling-specific or converge into a
  package-private shared `WorkerResultPublisher`.
- Whether capability/state reporting becomes a shared startup policy, remains
  polling-only, or becomes explicit opt-in behavior for both transports.
- Whether concrete session builders should keep all current common setters or
  delegate those setters to `WorkerSessionSpec.Builder` internally.
