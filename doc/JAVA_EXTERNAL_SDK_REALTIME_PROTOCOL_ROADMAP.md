# Java External SDK Realtime Protocol Roadmap

Status: proposed follow-up roadmap for
[`JAVA_EXTERNAL_SDK_REALTIME_DECISION.md`](./JAVA_EXTERNAL_SDK_REALTIME_DECISION.md).

This roadmap upgrades the Java external SDK from polling-only worker sessions
toward a broader worker SDK: polling remains the stable first path, while
WebSocket realtime sessions and a worker-local command runtime are designed as
explicit next layers. The goal is not only "support polling workers"; the goal
is a public external worker SDK that can own client-side session mechanics,
local command dispatch, and result adaptation without redefining platform
kernel ownership.

## Current Code Observations

- `integrations/xa-mass-java-sdk` is the pure external Java client artifact.
  It owns HTTP client ergonomics, worker topology calls, direct polling calls,
  and managed `PollingWorkerSession`.
- `WorkerSessions` currently exposes polling only. Public realtime Java
  sessions are intentionally deferred by
  [`JAVA_EXTERNAL_SDK_REALTIME_DECISION.md`](./JAVA_EXTERNAL_SDK_REALTIME_DECISION.md).
- `integrations/samples/java/worker-websocket` is a standalone sample POM that
  uses JDK `WebSocket` directly. It proves the current adapter path, but it is
  not yet an SDK-owned session API.
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
- Current WebSocket/socket realtime samples encode canonical task
  dispatch/result frames closely enough to justify a protocol design pass, but
  lifecycle, reconnect, command delivery, acknowledgement, and presence
  semantics are not yet a stable public SDK contract.

External source candidates to study during WSDK-0:

- AgentForge `command` and `clients` code as the source lineage for the worker
  command runtime concept.
- AgentForge WebSocket demo client code as a useful state-machine and
  transport-control reference, especially reliable reconnect, message queueing,
  and explicit disconnect-control handling.

Those assets are inputs for design and migration, not proof that the current XA
Mass SDK already owns those capabilities.

## Owner Review

External worker SDK capability belongs to `integrations/xa-mass-java-sdk`.

The Java SDK may own:

- public external worker topology clients over `/worker-api/v1/**`.
- managed external worker sessions.
- client-side transport lifecycle for public worker sessions.
- worker-local command dispatch keyed by `eventCode` or an explicitly
  documented command frame.
- conversion from local handler/command response into canonical task result or
  worker-command acknowledgement frames.

The Java SDK must not own:

- engine scheduling, matching, lease, result convergence, or terminal policy.
- transport server adapter implementation.
- worker-pack sample fault semantics.
- embedded runtime assembly from `xa-mass-sdk`.
- `xa-mass-base` as an upstream public SDK dependency.
- undocumented adapter-local realtime frames as a public compatibility
  promise.

Transport adapters may provide WebSocket/socket delivery mechanics, but they do
not define the public SDK surface alone. Worker-pack may consume the SDK when
the SDK owns a public client/session contract, but worker-pack must not become
a dependency of the SDK.

## Boundary Decision

Create a worker SDK layer inside `integrations/xa-mass-java-sdk` rather than a
new platform-kernel module.

The target split is:

```text
xa-mass-java-sdk
  worker topology client      HTTP control-plane calls
  polling worker session      stable polling data-plane loop
  realtime worker session     public WebSocket/socket client lifecycle, later
  command runtime             worker-local event/command handler registry

transport/*-adapter
  server-side delivery adapters and adapter-local runtime mechanics

integrations/xa-mass-worker-pack
  sample/dev worker capabilities, command routes, fault routes, launchers
```

The command runtime should be SDK-owned only as a worker-local execution
library. It must not be named or documented as task lifecycle command truth,
worker-control command truth, or engine command ownership.

Because `xa-mass-java-sdk` must stay a pure external client artifact, command
runtime convergence cannot be implemented by depending on `xa-mass-base`.
Useful command concepts may be migrated into SDK-owned packages, or split into
a later narrow public contract artifact, but `xa-mass-base` should not become
the dependency bridge for external callers.

## Target Shape

The Java SDK should eventually allow the same handler model to run behind
polling or realtime sessions:

```java
WorkerCommandRuntime commands = WorkerCommandRuntime.builder()
        .command("crawler.fetch-page", context -> {
            URI url = context.input().requiredUri("url");
            return CommandResult.success(Map.of("url", url.toString()));
        })
        .build();

PollingWorkerSession polling = mass.workerSessions().polling()
        .workerId("crawler-polling-001")
        .workerGroupId("crawler")
        .adapterNodeId("crawler-node-001")
        .commandRuntime(commands)
        .start();

WebSocketWorkerSession realtime = mass.workerSessions().webSocket()
        .workerId("crawler-ws-001")
        .workerGroupId("crawler")
        .endpoint(URI.create("ws://127.0.0.1:18088/ws"))
        .commandRuntime(commands)
        .start();
```

API names are placeholders until WSDK-1 fixes package and type names.

Preferred package direction:

```text
com.xa.mass.client.worker.session
com.xa.mass.client.worker.command
com.xa.mass.client.worker.transport
```

Avoid `com.xa.mass.sdk` because that package already means embedded SDK
compatibility. Avoid generic `CommandRuntime` as a top-level public name unless
the worker-local scope is clear from the package.

## Do Not Start With

Do not start by copying the AgentForge WebSocket or command code wholesale into
the SDK.

Start by fixing the public protocol and instance-owned runtime shape. The
tempting shortcut is to port the existing client and static command registry,
then retrofit ownership later. That would freeze adapter-local behavior before
the public worker session contract is clear.

## WSDK-0: Inventory And Protocol Classification

Scope:

- inventory current Java/Node polling, WebSocket, and socket worker samples.
- inventory current server-side realtime adapter frame handling and tests.
- inventory current `xa-mass-base` command packages by production and test
  callers.
- classify whether each command symbol should move into SDK-owned source,
  remain base-owned for embedded/event paths, or be deleted after consumers
  converge.
- inventory worker-pack sample command/fault routes separately from generic
  command runtime machinery.
- inventory AgentForge command and WebSocket client assets as migration
  candidates, classifying Android/OkHttp-specific pieces versus portable
  concepts.
- classify current realtime frames:
  - task dispatch frame.
  - task result frame.
  - heartbeat/control frame.
  - worker command frame.
  - command acknowledgement path.
  - reconnect/offline/presence behavior.

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
- any gap between current adapter behavior and target public protocol is named
  explicitly.

Verification candidates:

```powershell
rg -n "workerSessions|PollingWorkerSession|WorkerDispatchHandler|WorkerResult" integrations/xa-mass-java-sdk
rg -n "WebSocketWorkerMain|type=worker.command|worker.command|disconnect" integrations transport xa-mass-testing
rg -n "com\\.xa\\.mass\\.command" .
```

## WSDK-1: Worker Command Runtime Contract

Scope:

- define worker-local command runtime package, type names, and lifecycle.
- migrate the useful `command.core/model/runtime` concepts into SDK-owned
  source or a later narrow public contract artifact; do not reuse them through
  a production `xa-mass-base` dependency.
- shape the migrated concepts as an instance-scoped SDK contract:
  - command definition.
  - handler registry.
  - dispatch context.
  - typed payload access.
  - command response/result.
  - optional batch execution with bounded, documented context sharing.
- keep local services as explicit runtime configuration, not static global
  process state.
- define handler error conversion into failed task results for task dispatch.
- define separate conversion for worker-command acknowledgement if public
  realtime command frames are included later.

Out of scope:

- moving `command.event` into the Java external SDK.
- moving worker-pack sample/fault route implementations into the SDK.
- adding `xa-mass-base` as a production dependency of `xa-mass-java-sdk`.
- preserving static registry compatibility if all in-repo consumers can move.
- Android host integration.

Acceptance:

- command runtime can be instantiated multiple times in one JVM without shared
  handlers or mutable context leakage.
- unknown command/event returns a structured failure.
- handler exception behavior is deterministic and test-covered.
- public names make the worker-local scope clear.
- `xa-mass-java-sdk` production dependencies do not include `xa-mass-base`.
- worker-pack routes can be future consumers without forcing worker-pack into
  the SDK dependency graph.

Verification candidates:

```powershell
mvn -pl integrations/xa-mass-java-sdk -DskipITs test
rg -n "static .*CommandRegistry|CommandRegistry\\." integrations/xa-mass-java-sdk integrations/xa-mass-worker-pack xa-mass-base
```

## WSDK-2: Shared Worker Dispatch Adapter

Scope:

- extract the common execution path that turns a platform dispatch frame into a
  worker handler/command invocation and then into a canonical result.
- make polling session consume the shared adapter without changing polling
  public behavior.
- define a small internal frame model for task dispatch/result that can be
  reused by polling and future realtime sessions.
- keep topology/control-plane calls separate from dispatch execution.

Out of scope:

- changing server result convergence.
- adding realtime transport clients.
- hiding WorkerGroup declaration inside session startup.
- adding local retry after the server accepts a result.

Acceptance:

- polling session behavior remains source-compatible for existing SDK callers.
- handler execution and result conversion have focused tests independent of
  polling HTTP loops.
- no transport-specific shape becomes task kernel truth.
- polling black-box proof still passes after the refactor.

Verification candidates:

```powershell
mvn -pl integrations/xa-mass-java-sdk test
mvn -pl xa-mass-testing -Dtest=JavaPollingWorkerBlackBoxIntegrationTest test
```

## WSDK-3: Public WebSocket Worker Protocol Contract

Scope:

- document the public WebSocket worker endpoint and handshake.
- document task dispatch and task result frames.
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
- protocol contract names every public frame field and retry/idempotency rule.
- existing Java WebSocket sample behavior can be mapped to the contract or the
  mismatch is listed as a required adapter/sample change.

Verification candidates:

```powershell
mvn -pl xa-mass-testing -Dtest=JavaWebSocketWorkerBlackBoxIntegrationTest test
mvn -q -f integrations/samples/java/worker-websocket/pom.xml -DskipTests package
```

## WSDK-4: Reliable WebSocket Worker Session

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
- route dispatch through the shared worker command/handler adapter from WSDK-2.
- keep lifecycle callbacks observable for startup failure, reconnect, handler
  failure, result send failure, and terminal close.

Out of scope:

- Android `HandlerThread`, Android `Context`, and OkHttp dependencies in
  `xa-mass-java-sdk`.
- local durable queueing.
- socket client support.
- worker-pack sample fault behavior.

Acceptance:

- WebSocket session is usable without raw frame parsing by normal callers.
- reconnect does not duplicate a result after the server has accepted it.
- stale socket callbacks cannot mutate the current session state.
- heartbeat/control frames do not reach the worker command runtime unless the
  protocol explicitly says they should.
- session close is idempotent and best-effort offline behavior is documented.

Verification candidates:

```powershell
mvn -pl integrations/xa-mass-java-sdk test
mvn -pl xa-mass-testing -Dtest=JavaWebSocketWorkerBlackBoxIntegrationTest test
```

## WSDK-5: Java WebSocket Sample Convergence

Scope:

- update `integrations/samples/java/worker-websocket` to consume
  `xa-mass-java-sdk` WebSocket worker sessions.
- make the sample's event handlers use the worker command runtime when useful.
- keep sample output evidence used by black-box tests, including worker
  identity, adapter/transport, eventCode, and integration probe fields.
- decide intentionally whether the sample remains a standalone `-f` POM or
  joins the root reactor.
- update sample README and black-box process paths in the same slice.

Out of scope:

- Node sample migration.
- socket sample migration.
- worker-pack module relocation.

Acceptance:

- Java WebSocket sample no longer owns raw reconnect/frame boilerplate that the
  SDK should own.
- black-box Java WebSocket worker proof still passes.
- sample build ownership is documented and matches Maven wiring.
- no sample-only helper is promoted into the public SDK without an owner
  decision.

Verification candidates:

```powershell
mvn -q -f integrations/samples/java/worker-websocket/pom.xml -DskipTests package
mvn -pl xa-mass-testing -Dtest=JavaWebSocketWorkerBlackBoxIntegrationTest test
```

## WSDK-6: Worker-Pack Command Runtime Convergence

Scope:

- retarget worker-pack sample command/fault runtime to the SDK command runtime
  only after WSDK-1 is stable.
- keep sample command route definitions in worker-pack.
- keep fault injection behavior in worker-pack and fault-matrix docs.
- remove duplicated command runtime machinery from worker-pack or base only
  after all current callers are moved and `command.event` usage is separately
  classified.

Out of scope:

- moving `command.event` behavior.
- deleting command packages before caller convergence.
- making worker-pack a dependency of the Java SDK.
- treating worker-pack sample fault behavior as public SDK behavior.

Acceptance:

- worker-pack uses the SDK command runtime as a consumer, not as an owner.
- command/fault sample routes remain clearly sample/dev capabilities.
- no two live generic command runtimes remain for the same worker-local
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
  - transport adapter implementation packages.
- `xa-mass-java-sdk` production code must not depend on worker-pack.
- `xa-mass-java-sdk` production code must not depend on `xa-mass-base`.
- `xa-mass-java-sdk` production code must not depend on Android, OkHttp, or
  embedded runtime composition.
- server and transport production code must not depend on
  `xa-mass-java-sdk`.
- worker-pack and samples may depend on `xa-mass-java-sdk`.
- public realtime client methods must map to a documented protocol frame.
- command runtime tests must prove instance isolation.

## Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Realtime API freezes adapter-local behavior too early | public compatibility debt | require WSDK-3 protocol contract before WSDK-4 implementation |
| Static command registry leaks between worker sessions | multi-worker JVM bugs | make command runtime instance-scoped in WSDK-1 |
| SDK depends on `xa-mass-base` to reuse command code | external SDK inherits internal platform dependency surface | migrate command concepts into SDK-owned source or split a narrow public contract artifact |
| Command naming collides with task lifecycle commands | owner confusion | use worker-local package and wording consistently |
| WebSocket reconnect duplicates accepted results | result convergence noise | document idempotency and test reconnect/result-send edges |
| Worker-pack fault behavior moves into SDK | public SDK surface becomes sample-specific | keep fault routes in worker-pack |
| Android/OkHttp code enters pure Java SDK | dependency and platform drift | port concepts, not dependencies |
| WebSocket sample build wiring drifts | black-box proofs break | decide standalone vs reactor ownership in WSDK-5 |

## Verification Matrix

| Phase | Verification |
| --- | --- |
| WSDK-0 | inventory review and source search only |
| WSDK-1 | Java SDK unit tests for command runtime isolation and error conversion |
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
- Do not introduce compatibility aliases for in-repo command runtime movement.
- Do not hide WorkerGroup declaration inside realtime session startup.
- Do not collapse `adapterId` and `transportHint` into an implicit transport
  guess.
