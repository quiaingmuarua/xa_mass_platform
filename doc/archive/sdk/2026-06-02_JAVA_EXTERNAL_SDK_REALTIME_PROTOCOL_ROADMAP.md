# Java External SDK Realtime Protocol Roadmap

Archive status: first WebSocket session slice implemented and archived on
2026-06-02. Current Java SDK worker-session truth lives in
`sdk/xa-mass-java-sdk` docs and tests. Remaining realtime hardening decisions
are tracked by `roadmap/JAVA_EXTERNAL_SDK_REALTIME_SESSION_HARDENING_DECISION.md`.

Status: implemented first WebSocket session slice. This is not yet final
long-running realtime SDK hardening. Follow-up lifecycle and worker-pack
capability hardening is tracked by
[`INTEGRATIONS_EXTERNAL_SDK_WORKER_PACK_HARDENING_ROADMAP.md`](../doc/archive/integrations/2026-06-02_INTEGRATIONS_EXTERNAL_SDK_WORKER_PACK_HARDENING_ROADMAP.md).
This roadmap supersedes the
archived
[`JAVA_EXTERNAL_SDK_REALTIME_DECISION.md`](../doc/archive/integrations/2026-05-28_JAVA_EXTERNAL_SDK_REALTIME_DECISION.md)
decision record that deferred realtime SDK support.

This roadmap upgrades the Java external SDK from polling-only worker sessions
toward a broader worker SDK: polling remains the stable first path, while
WebSocket realtime sessions and a worker-local event handler runtime are
designed as explicit next layers. The goal is not only "support polling
workers"; the goal is a public external worker SDK that can own client-side
session mechanics, transport-independent handler invocation, and result
adaptation without redefining platform kernel ownership.

## Current Code Observations

- `sdk/xa-mass-java-sdk` is the pure external Java client artifact.
  It owns HTTP client ergonomics, worker topology calls, direct polling calls,
  and managed `PollingWorkerSession`.
- `WorkerSessions` exposes polling and WebSocket sessions. Socket remains out
  of the Java SDK.
- `com.xa.mass.client.worker.handler` now owns the minimum transport-neutral
  worker event handler runtime: handler definition, immutable handler
  registry, instance-scoped invocation runtime, invocation result, and result
  sink.
- `PollingWorkerSession` adapts polling dispatch into the SDK handler runtime
  and reports through a session-owned result sink backed by worker HTTP submit
  by default.
- `WebSocketWorkerSession` adapts canonical WebSocket dispatch frames into the
  same SDK handler runtime and sends canonical result frames through a bounded
  outbound queue.
- `integrations/samples/java` has been retired. Java WebSocket proof now runs
  through scenario-launcher and `workerSessions().webSocket()`.
- `integrations/xa-mass-worker-pack` owns sample/dev worker behavior. Its
  command/fault routes are useful SDK input, but sample fault behavior must not
  become public SDK behavior.
- `xa-mass-base/src/main/java/com/xa/mass/command` is mixed:
  - `command.event` is used by embedded SDK/event runtime paths.
  - `command.core`, `command.model`, and `command.runtime` are currently used
    by worker-pack sample command execution.
- even though the worker command code lineage has already been extracted into
  `xa-mass-base`, `xa-mass-base` is not a valid production dependency for the
  external Java SDK. Treat that package as current in-repo placement, not as
  the future public SDK contract owner.
- Current WebSocket session support covers realtime worker registration, task
  dispatch/result frames, reconnect attempts, and adapter-owned presence. The
  scenario-launcher black-box proof covers the normal happy path. Close
  behavior with queued results, reconnect terminal outcomes, queue-full
  handling, and command/fault harness behavior are not final public SDK
  guarantees.

External source candidates to study during WSDK-0:

- AgentForge `command` and `clients` code as the source lineage for the worker
  event handler runtime concept.
- AgentForge WebSocket demo client code as a useful state-machine and
  transport-control reference, especially reliable reconnect, message queueing,
  and explicit disconnect-control handling.

Those assets are inputs for design and migration, not proof that the current XA
Mass SDK already owns those capabilities.

## Owner Review

External worker SDK capability belongs to `sdk/xa-mass-java-sdk`.

The Java SDK may own:

- public external worker topology clients over `/worker-api/v1/**`.
- managed external worker sessions.
- client-side transport lifecycle for public worker sessions.
- worker-local event handler invocation keyed by `eventCode`.
- conversion from local handler response into canonical task result or
  worker-command acknowledgement frames.

The Java SDK must not own:

- engine scheduling, matching, lease, result convergence, or terminal policy.
- transport server adapter implementation.
- worker-pack sample fault semantics.
- embedded runtime assembly from `xa-mass-embedded-sdk`.
- `xa-mass-base` as an upstream public SDK dependency.
- undocumented adapter-local realtime frames as a public compatibility
  promise.

Transport adapters may provide WebSocket/socket delivery mechanics, but they do
not define the public SDK surface alone. Worker-pack may consume the SDK when
the SDK owns a public client/session contract, but worker-pack must not become
a dependency of the SDK.

## Boundary Decision

Create a worker SDK layer inside `sdk/xa-mass-java-sdk` rather than a
new platform-kernel module.

The target split is:

```text
xa-mass-java-sdk
  worker topology client      HTTP control-plane calls
  polling worker session      stable polling data-plane loop
  realtime worker session     public WebSocket/socket client lifecycle, later
  event handler runtime       transport-independent eventCode handler registry

transport/*-adapter
  server-side delivery adapters and adapter-local runtime mechanics

integrations/xa-mass-worker-pack
  sample/dev worker capabilities, command routes, fault routes, launchers
```

The event handler runtime should be SDK-owned only as a worker-local execution
library. It must not be named or documented as task lifecycle command truth,
worker-control command truth, or engine command ownership. Existing
`command.*` code is source lineage and migration input, not the target public
SDK name.

Because `xa-mass-java-sdk` must stay a pure external client artifact, command
runtime convergence cannot be implemented by depending on `xa-mass-base`.
Useful command concepts should be migrated into SDK-owned event handler source
by default. Splitting a later narrow public contract artifact requires WSDK-0
evidence of multiple real public/external Java consumers and a separate owner
decision; it is not the default first move.

## Hard Rules

1. `xa-mass-java-sdk` production code must not depend on `xa-mass-base`,
   worker-pack, engine, server, transport adapter implementations, or embedded
   SDK runtime composition.
2. The worker event handler runtime is transport-independent. Polling,
   WebSocket, and socket sessions may deliver frames to it, but they must not
   define handler ownership.
3. The worker event handler runtime must be instance-scoped, never
   process-global or static-registry based.
4. No SDK transport shape may become engine/task kernel truth.
5. Worker-pack sample/fault behavior must not enter the public SDK surface.
6. Reconnect must not duplicate a result after the server has accepted it.
7. Public session APIs must not expose undocumented adapter-local frames as a
   stable compatibility promise.
8. Handler completion and result reporting are separate concerns. A handler may
   return a result directly, or enqueue/report it through a session-owned
   result sink, but network transports own only delivery of that result.

## Target Shape

The Java SDK should eventually allow the same handler model to run behind
polling or realtime sessions:

```java
WorkerEventHandlers handlers = WorkerEventHandlers.builder()
        .event("crawler.fetch-page", context -> {
            URI url = context.input().requiredUri("url");
            return WorkerHandlerResult.success(Map.of("url", url.toString()));
        })
        .build();

PollingWorkerSession polling = mass.workerSessions().polling()
        .workerId("crawler-polling-001")
        .workerGroupId("crawler")
        .adapterNodeId("crawler-node-001")
        .eventHandlers(handlers)
        .resultQueue(WorkerResultQueue.bounded(1024))
        .start();

WebSocketWorkerSession realtime = mass.workerSessions().webSocket()
        .workerId("crawler-ws-001")
        .workerGroupId("crawler")
        .endpoint(URI.create("ws://127.0.0.1:18088/ws"))
        .eventHandlers(handlers)
        .resultQueue(WorkerResultQueue.bounded(1024))
        .start();
```

`WorkerEventHandlers` is the stable-proposed handler registry name.
`resultQueue(...)` remains a placeholder for a later queue abstraction; the
implemented minimum capability is `WorkerResultSink`, a session-owned result
reporting target that polling uses now and realtime sessions can back with a
frame sender later.

Preferred package direction:

```text
com.xa.mass.client.worker.session
com.xa.mass.client.worker.handler
com.xa.mass.client.worker.payload
com.xa.mass.client.worker.transport
```

Avoid `com.xa.mass.sdk` because that package already means embedded SDK
compatibility. Avoid generic `CommandRuntime` as a top-level public name. The
public SDK concept is event handler execution; `command` remains a migration
term for the current source package and worker command wire frames.

## Do Not Start With

Do not start by copying the AgentForge WebSocket or command code wholesale into
the SDK.

Start by fixing the public protocol and instance-owned runtime shape. The
tempting shortcut is to port the existing client and static command registry,
then retrofit ownership later. That would freeze adapter-local behavior before
the public worker session contract is clear and would mix handler execution
with network transport concerns.

## WSDK-0: Inventory And Protocol Classification

Scope:

- inventory current Java/Node polling, WebSocket, and socket worker samples.
- inventory current server-side realtime adapter frame handling and tests.
- inventory current `xa-mass-base` command packages by production and test
  callers.
- classify whether each command symbol should move into SDK-owned source,
  remain base-owned for embedded/event paths, or be deleted after consumers
  converge.
- classify `command.event` explicitly as part of the same inventory. It is not
  part of the worker event handler runtime unless a later owner decision says
  otherwise.
- classify every static registration, static dispatch, and singleton context
  entry point by target lifecycle: SDK instance-scoped, non-SDK static legacy,
  or residue.
- inventory worker-pack sample command/fault routes separately from generic
  event handler runtime machinery.
- inventory AgentForge command and WebSocket client assets as migration
  candidates, classifying Android/OkHttp-specific pieces versus portable
  concepts.
- classify current realtime frames:
  - task dispatch frame.
  - task result frame.
  - active result report frame or HTTP submit path.
  - heartbeat/control frame.
  - worker command frame.
  - command acknowledgement path.
  - reconnect/offline/presence behavior.
- inventory Sekiro-style Java SDK concepts that are useful but should remain
  SDK/client-side concerns:
  - action/event handler registration.
  - request/response style handler completion.
  - async enqueue/callback invocation.
  - lifecycle listeners.
  - configurable handler execution pool.
  - optional payload/result compression.

Out of scope:

- implementation.
- public realtime session API.
- moving command code between modules.
- adding a production dependency from `xa-mass-java-sdk` to `xa-mass-base`.
- adding WebSocket sample POMs to the root reactor.

Acceptance:

- a sibling inventory lists each current caller and each candidate source
  symbol before code moves.
- worker-pack sample fault routes are classified as sample/dev behavior, not
  SDK behavior.
- every realtime frame family has an owner and target public/adapter-local
  classification.
- every `xa-mass-base` command symbol has a target dependency answer:
  SDK-owned source, base-owned non-SDK source, or residue.
- every static command registration/dispatch/context entry point has a target
  lifecycle answer.
- result reporting has a target shape for both direct handler return and
  active enqueue/report completion.
- any gap between current adapter behavior and target public protocol is named
  explicitly.

Verification candidates:

```powershell
rg -n "workerSessions|PollingWorkerSession|WorkerDispatchHandler|WorkerResult" sdk/xa-mass-java-sdk
rg -n "WebSocketWorkerMain|type=worker.command|worker.command|disconnect" integrations transport xa-mass-testing
rg -n "com\\.xa\\.mass\\.command" .
```

## WSDK-1: Worker Event Handler Runtime Contract

Status: minimum implemented in `sdk/xa-mass-java-sdk`.

Scope:

- define worker-local event handler runtime package, type names, and lifecycle.
- migrate the useful `command.core/model/runtime` concepts into SDK-owned event
  handler source. Do not reuse them through a production `xa-mass-base`
  dependency.
- do not split a new public contract artifact in this slice unless WSDK-0
  proves multiple real public/external Java consumers and records a separate
  owner decision.
- shape the migrated concepts as an instance-scoped SDK contract:
  - event handler definition.
  - handler registry.
  - dispatch context.
  - typed payload access.
  - handler response/result.
  - active result sink for asynchronous handler completion.
  - optional batch execution with bounded, documented context sharing.
- keep local services as explicit runtime configuration, not static global
  process state.
- define handler error conversion into failed task results for task dispatch.
- define whether asynchronous handler completion is allowed in WSDK-1 or only
  reserved as a typed API placeholder for WSDK-2.
- define separate conversion for worker-command acknowledgement if public
  realtime command frames are included later.

Out of scope:

- moving `command.event` into the Java external SDK.
- moving worker-pack sample/fault route implementations into the SDK.
- adding `xa-mass-base` as a production dependency of `xa-mass-java-sdk`.
- preserving static registry compatibility if all in-repo consumers can move.
- Android host integration.

Acceptance:

- event handler runtime can be instantiated multiple times in one JVM without
  shared handlers or mutable context leakage.
- unknown event or worker-command frame returns a structured failure.
- handler exception behavior is deterministic and test-covered.
- public names make the worker-local scope clear.
- `xa-mass-java-sdk` production dependencies do not include `xa-mass-base`.
- WSDK-1 guard blocks both a Maven dependency on `xa-mass-base` and production
  imports from `com.xa.mass.command..`.
- event handler runtime has no dependency on polling, WebSocket, socket, or
  transport adapter implementation types.
- active result sink API, if exposed, is transport-independent and can be
  backed by polling HTTP submit or realtime frame send.
- worker-pack routes can be future consumers without forcing worker-pack into
  the SDK dependency graph.

Verification candidates:

```powershell
mvn -pl sdk/xa-mass-java-sdk -DskipITs test
rg -n "static .*CommandRegistry|CommandRegistry\\." sdk/xa-mass-java-sdk integrations/xa-mass-worker-pack xa-mass-base
```

## WSDK-2: Worker Event Handler Invocation Layer

Status: minimum implemented for polling frame adaptation and result sink
delivery. Realtime frame adaptation and result queueing remain follow-up.

Scope:

- extract the common execution path that turns a platform dispatch frame into a
  worker event handler invocation and then into a canonical result.
- make polling session consume the shared invocation layer without changing
  polling public behavior.
- add a session-owned result sink/queue abstraction that can accept handler
  results from direct returns or asynchronous completion.
- define a small internal frame model for task dispatch/result that can be
  reused by polling and future realtime sessions.
- keep topology/control-plane calls separate from dispatch execution.
- keep the invocation layer free of WebSocket/socket/polling-specific lifecycle
  state. Network sessions adapt frames into this layer; the layer does not own
  network transport.
- define queue bounds, overflow behavior, close behavior, and whether
  backpressure is caller-visible.

Out of scope:

- changing server result convergence.
- adding realtime transport clients.
- hiding WorkerGroup declaration inside session startup.
- adding local retry after the server accepts a result.
- durable result queueing.

Acceptance:

- polling session behavior remains source-compatible for existing SDK callers.
- handler execution and result conversion have focused tests independent of
  polling HTTP loops.
- no transport-specific shape becomes task kernel truth.
- handler invocation tests can run without starting any network transport.
- result sink tests cover direct return, async enqueue, overflow, close, and
  duplicate-completion behavior without starting any network transport.
- polling black-box proof still passes after the refactor.

Verification candidates:

```powershell
mvn -pl sdk/xa-mass-java-sdk test
mvn -pl xa-mass-server -am "-Dtest=JavaScenarioLauncherBlackBoxIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## WSDK-3: Public WebSocket Worker Protocol Contract

Status: implemented minimum contract.

Scope:

- document the public WebSocket worker endpoint and handshake.
- document task dispatch and task result frames.
- document whether active result report uses the WebSocket connection, direct
  HTTP submit, or a transport-neutral session sink backed by adapter-specific
  delivery.
- document heartbeat and transport-control frames.
- document reconnect behavior, duplicate result behavior, offline/presence
  behavior, and close/shutdown semantics.
- decide whether worker command delivery is included in the first WebSocket
  session or remains polling-only/direct-HTTP.
- decide whether the first API is WebSocket-specific
  `workerSessions().webSocket()` or transport-neutral
  `workerSessions().realtime()` with WebSocket as the only implementation.

Out of scope:

- socket client implementation.
- changing adapter protocol solely to match AgentForge.
- freezing undocumented worker-pack control frames as public protocol.

Acceptance:

- protocol doc distinguishes transport-control frames from task/command
  command frames.
- connection presence is adapter-owned and not confused with worker
  registration truth.
- command acknowledgement path is either documented or explicitly deferred.
- active result reporting path is documented for both immediate and delayed
  handler completion, or explicitly deferred.
- protocol contract names every public frame field and retry/idempotency rule.
- existing Java WebSocket sample behavior can be mapped to the contract or the
  mismatch is listed as a required adapter/sample change.

Verification candidates:

```powershell
mvn -pl xa-mass-server -am "-Dtest=JavaScenarioLauncherBlackBoxIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## WSDK-4: Reliable WebSocket Worker Session

Status: implemented first production SDK session.

Scope:

- implement a JDK `HttpClient`/`WebSocket` based managed worker session in
  `xa-mass-java-sdk`.
- port concepts from the AgentForge reliable client where they fit the Java
  SDK:
  - explicit connection states.
  - generation token to ignore stale callbacks.
  - bounded outbound queue.
  - reconnect decider and backoff.
  - listener callbacks for open/message/error/closed/reconnect.
  - explicit transport-control disconnect handling.
- receive canonical task dispatch frames and submit canonical task result
  frames through the WebSocket connection.
- route dispatch through the worker event handler invocation layer from WSDK-2.
- flush results from the shared result sink/queue through the WebSocket
  session, preserving idempotency rules from WSDK-3.
- keep lifecycle callbacks observable for startup failure, reconnect, handler
  failure, result send failure, and terminal close.

Out of scope:

- Android `HandlerThread`, Android `Context`, and OkHttp dependencies in
  `xa-mass-java-sdk`.
- local durable queueing.
- unbounded in-memory result queues.
- socket client support.
- worker-pack sample fault behavior.

Acceptance:

- WebSocket session is usable without raw frame parsing by normal callers.
- reconnect does not duplicate a result after the server has accepted it.
- queued results are either flushed, failed visibly, or rejected according to
  documented close/backpressure policy.
- stale socket callbacks cannot mutate the current session state.
- heartbeat/control frames do not reach the worker event handler runtime unless
  the protocol explicitly says they should.
- session close is idempotent and best-effort offline behavior is documented.

Verification candidates:

```powershell
mvn -pl sdk/xa-mass-java-sdk test
mvn -pl xa-mass-server -am "-Dtest=JavaScenarioLauncherBlackBoxIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## WSDK-5: Java WebSocket Proof Convergence

Status: implemented through scenario-launcher. Strategic adoption and sample
retirement were completed by the archived
[`INTEGRATIONS_JAVA_SDK_ADOPTION_ROADMAP.md`](../doc/archive/integrations/2026-06-01_INTEGRATIONS_JAVA_SDK_ADOPTION_ROADMAP.md).

Scope:

- if worker-pack or scenario-launcher can provide the same WebSocket proof
  through an SDK-backed strategic consumer, use that proof instead and demote
  or remove the standalone Java WebSocket sample.
- keep output evidence used by black-box tests, including worker
  identity, adapter/transport, eventCode, and integration probe fields.

Out of scope:

- Node sample migration.
- socket sample migration.
- worker-pack module relocation.

Acceptance:

- Java WebSocket proof no longer relies on raw reconnect/frame boilerplate that
  the SDK should own.
- black-box Java WebSocket worker proof still passes through either the
  transitional sample or an SDK-backed strategic consumer.
- if the standalone sample remains, sample build ownership is documented and
  matches Maven wiring, and the sample has an explicit retirement trigger in
  the integrations adoption roadmap.
- no sample-only helper is promoted into the public SDK without an owner
  decision.

Verification candidates:

```powershell
mvn -pl xa-mass-server -am "-Dtest=JavaScenarioLauncherBlackBoxIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## WSDK-6: Worker-Pack Event Handler Runtime Convergence

Scope:

- retarget worker-pack sample command/fault runtime to the SDK event handler
  runtime only after WSDK-1 is stable.
- keep sample command route definitions in worker-pack.
- keep fault injection behavior in worker-pack and fault-matrix docs.
- remove duplicated event handler runtime machinery from worker-pack or base
  only after all current callers are moved and `command.event` usage is
  separately classified.

Out of scope:

- moving `command.event` behavior.
- deleting command packages before caller convergence.
- making worker-pack a dependency of the Java SDK.
- treating worker-pack sample fault behavior as public SDK behavior.

Acceptance:

- worker-pack uses the SDK event handler runtime as a consumer, not as an owner.
- command/fault sample routes remain clearly sample/dev capabilities.
- no two live generic event handler runtimes remain for the same worker-local
  responsibility.
- base command residue is either removed, narrowed, or documented as still
  owned by an active caller.

Verification candidates:

```powershell
mvn -pl integrations/xa-mass-worker-pack test
rg -n "com\\.xa\\.mass\\.command\\.(core|model|runtime)" xa-mass-base integrations
```

## WSDK-7: Socket And Android Follow-Up Decision

Scope:

- decide whether socket gets a first-class Java SDK session after WebSocket.
- decide whether AgentForge Android command/WebSocket host code should become
  a separate Android worker host artifact.
- decide whether a transport-neutral `RealtimeWorkerSession` abstraction is
  justified after at least two realtime transports share enough lifecycle.

Out of scope:

- adding abstraction before WebSocket proves the lifecycle surface.
- pulling Android dependencies into the pure Java SDK.
- replacing polling as the recommended stable first external worker path.

Acceptance:

- socket and Android decisions are recorded before implementation.
- shared realtime abstraction is added only if it protects a real public
  caller surface rather than hiding adapter differences.

## Architecture Guards

Add or extend guards before public WebSocket session support lands:

- `xa-mass-java-sdk` production code must not import:
  - `com.xa.mass.engine..`
  - `com.xa.mass.starter..`
  - `com.xa.mass.worker.runtime..`
  - `com.xa.mass.api.internal..`
  - `com.xa.mass.command..`
  - transport adapter implementation packages.
- `xa-mass-java-sdk` Maven dependencies must not include `xa-mass-base` or
  `xa-mass-worker-pack`.
- `xa-mass-java-sdk` production code must not depend on worker-pack.
- `xa-mass-java-sdk` production code must not depend on `xa-mass-base`.
- `xa-mass-java-sdk` production code must not depend on Android, OkHttp, or
  embedded runtime composition.
- server and transport production code must not depend on
  `xa-mass-java-sdk`.
- worker-pack and samples may depend on `xa-mass-java-sdk`.
- public realtime client methods must map to a documented protocol frame.
- event handler runtime tests must prove instance isolation.

## Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Realtime API freezes adapter-local behavior too early | public compatibility debt | require WSDK-3 protocol contract before WSDK-4 implementation |
| Static command registry leaks between worker sessions | multi-worker JVM bugs | make event handler runtime instance-scoped in WSDK-1 |
| SDK depends on `xa-mass-base` to reuse command code | external SDK inherits internal platform dependency surface | migrate command concepts into SDK-owned event handler source by default |
| Event handler runtime absorbs transport lifecycle | handler code becomes WebSocket/polling specific | keep transport sessions as frame adapters into WSDK-2 invocation layer |
| Result sink becomes hidden local durability | users think queued results survive process death | keep first sink in-memory, bounded, and visibly best-effort unless a later durable design is approved |
| Async completion reports duplicate results | terminal/result convergence noise | track one completion per dispatch identity and make duplicate completion a visible failure |
| Command naming collides with task lifecycle commands | owner confusion | use event handler naming for public SDK and keep command as migration/wire-frame term |
| WebSocket reconnect duplicates accepted results | result convergence noise | document idempotency and test reconnect/result-send edges |
| Worker-pack fault behavior moves into SDK | public SDK surface becomes sample-specific | keep fault routes in worker-pack |
| Android/OkHttp code enters pure Java SDK | dependency and platform drift | port concepts, not dependencies |
| WebSocket sample build wiring drifts | black-box proofs break | decide standalone vs reactor ownership in WSDK-5 |

## Verification Matrix

| Phase | Verification |
| --- | --- |
| WSDK-0 | inventory review and source search only |
| WSDK-1 | Java SDK unit tests for event handler runtime isolation and error conversion |
| WSDK-2 | Java SDK unit tests plus polling black-box proof |
| WSDK-3 | protocol doc review plus current WebSocket sample black-box baseline |
| WSDK-4 | Java SDK unit tests plus Java WebSocket black-box proof |
| WSDK-5 | sample package command plus Java WebSocket black-box proof |
| WSDK-6 | worker-pack tests and command package residue scan |
| WSDK-7 | decision record only |

Full reactor is not required after every design slice. Implementation slices
that affect worker lifecycle should run the relevant black-box proof because
session bugs usually appear through dispatch/result convergence rather than
unit tests alone.

## Non-Goals

- Do not change engine scheduling, result convergence, or task lifecycle
  policy.
- Do not make realtime the default external worker path before protocol proof.
- Do not expose WebSocket/socket frames as public SDK behavior before WSDK-3.
- Do not move worker-pack sample fault behavior into the Java SDK.
- Do not depend on transport adapter implementation modules from the Java SDK.
- Do not depend on `xa-mass-base` from the Java SDK just to reuse command
  runtime code.
- Do not pull Android, OkHttp, or AgentForge host dependencies into the pure
  Java SDK.
- Do not introduce compatibility aliases for in-repo event handler runtime
  movement.
- Do not hide WorkerGroup declaration inside realtime session startup.
- Do not collapse `adapterId` and `transportHint` into an implicit transport
  guess.
