# XA Mass Embedded SDK

Status: current embedded SDK runtime-composition owner README.

`xa-mass-embedded-sdk` is the real Java embedding module for XA Mass Platform.

It carries both:

- the embedded runtime composition, split between SDK-facing builder/facade types (`com.xa.mass.starter.*`), SDK-owned transport composition (`com.xa.mass.sdk.transport.*`), and shared transport runtime assembly (`com.xa.mass.transport.runtime.*`)
- the in-process embedding facade (`com.xa.mass.sdk.*`)

The runtime composition has been folded into this artifact so library callers
can depend on one embedded SDK module without pulling the HTTP/demo control surface.
Stable SDK-facing catalog/auth/model contracts now live in the internal
`xa-mass-embedded-sdk-api` module and are pulled transitively through this artifact.
Transport-neutral runtime contracts now live in `xa-mass-transport-api`; the
current bundled transport adapters include polling plus realtime adapters such
as WebSocket and socket. Adapter/bootstrap ownership lives in adapter modules
such as `xa-mass-transport-websocket` and `xa-mass-transport-socket`.
`xa-mass-embedded-sdk` assembles worker transports through a transport runtime
registry/factory seam instead of hiding a websocket-first default runtime.

## Dependency

```xml
<dependency>
  <groupId>com.xa.mass</groupId>
  <artifactId>xa-mass-embedded-sdk</artifactId>
  <version>${xa.mass.version}</version>
</dependency>
```

## Quick Start

Create an SDK application handle:

```java
import com.xa.mass.sdk.MassSdk;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.auth.CredentialPrincipalProfile;
import com.xa.mass.sdk.auth.CredentialPrincipalRegistration;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.sdk.model.MassTaskItemBatchAppendRequest;
import com.xa.mass.sdk.model.MassTaskShellCreateRequest;
import com.xa.mass.sdk.model.WorkerEventBinding;
import com.xa.mass.sdk.model.WorkerRegistration;

MassSdkApplication app = MassSdk.builder()
        .transport(transport -> transport
                .webSocketAdapter(webSocket -> webSocket
                        .adapterId("ws-public")
                        .server(19090, "/ws")
                        .enabled(false)
                        .serverEnabled(false)))
        .engine(engine -> engine.enabled(true).workerThreads(4))
        .build();

Mainline transport configuration is adapter-owned: use nested
`webSocketAdapter(...)`, `socketAdapter(...)`, or other explicit adapter blocks.
Start from `MassSdk.builder()` and assemble the adapters you actually want.
When the same adapter type needs multiple concrete runtime instances, keep one
bundled slot with `webSocketAdapter(...)` / `socketAdapter(...)` and add extra
instances through `addWebSocketAdapter(...)` / `addSocketAdapter(...)`, each
with an explicit `adapterId`.

app.start();

app.declareWorkerGroup(WorkerGroupDeclaration.builder()
        .groupId("crawler")
        .eventBindings(java.util.List.of(
                WorkerEventBinding.builder()
                        .eventCode("demo.dispatch")
                        .projectCodes(java.util.List.of("demoApp"))
                        .build()
        ))
        .build());

app.registerAdapterNode(AdapterNodeRegistration.builder()
        .adapterNodeId("crawler-polling-node")
        .adapterType("polling")
        .endpointId("crawler-polling-node")
        .build());

app.bindNodeGroup(NodeGroupBindingRegistration.builder()
        .adapterNodeId("crawler-polling-node")
        .workerGroupId("crawler")
        .build());

app.registerWorker(WorkerRegistration.builder()
        .workerId("crawler-worker-1")
        .adapterNodeId("crawler-polling-node")
        .workerGroupId("crawler")
        .transportHint("polling")
        .attributes(java.util.Map.of("type", "crawler"))
        .build());

var task = app.createTaskShell(MassTaskShellCreateRequest.builder()
        .userId("agent")
        .project("demoApp")
        .sharedConfig(java.util.Map.of("textContent", "hello", "routingCode", "us"))
        .executionSpec(new TaskExecutionSpec())
        .build());

app.appendTaskItems(task.getTid(), MassTaskItemBatchAppendRequest.builder()
        .eventCode("demo.dispatch")
        .items(java.util.List.of(
                java.util.Map.of("target", "target-a")))
        .build());

app.executeTaskCommand(task.getTid(), com.xa.mass.sdk.model.MassTaskCommandRequest.builder()
        .command("SEAL")
        .build());

app.pullWorker("crawler-worker-1").connect();
```

New worker capability registration should declare `WorkerGroupDeclaration`
with `eventBindings`, then register worker execution identities against the
group. `WorkerRegistration` does not accept worker-level capability fields;
use worker attributes only for routing labels and diagnostics.

`WorkerContext` registration, query, and runtime payload surfaces have been
removed from the SDK mainline. New SDK integration should start from
`WorkerGroupDeclaration`, `WorkerRegistration`, transport identity, and
external worker client flows.

`transportHint` is required for worker registration, and `adapterId` is the concrete runtime identity. Registration resolution now comes from transport runtime metadata rather than SDK-side `realtime -> websocket` guessing. Realtime workers must always register with explicit `adapterId + transportHint`; only polling keeps the implicit family default to `polling`. `pullWorker(...)` also resolves strictly from the worker's declared transport identity and fails fast on transport mismatch instead of falling back to another pull-capable adapter. Adapter-id aliases such as `ws`, `pull`, `queue`, or `tcp-socket` are not accepted as runtime identities; use canonical adapter ids such as `websocket`, `polling`, or `socket`. `transportHint` aliases such as `websocket`, `ws`, `push`, `pull`, or `queue` are also not accepted; use canonical coarse families such as `realtime` or `polling`. Adapter implementation labels such as `WorkerAdapter.protocol()` are no longer treated as runtime transport truth; selection keys off canonical registration identity instead.

When runtime reachability needs cross-instance truth, configure shared transport
route ownership through `redisDistributedChannels(...)`,
`redisRouteOwnerStore(...)`, or `routeOwnerStoreFactory(...)`. Adapters still
own local session/connect/heartbeat ingress, but shared route-owner evidence
belongs to transport runtime rather than engine-local worker status. `routeKey`
locates the transport delivery universe, such as a worker-group lane or a
future adapter/group lane minted outside transport. Individual dispatch items
may still carry the engine-selected `workerId` as an execution constraint;
distributed handoff partitions the already assigned item to that worker's
current route consumer node. Worker runtime capacity and multi-binding behavior
remain owned by worker-runtime scheduling/admission, not by transport route
ownership.

Task result reads are exposed through `TaskResultQueryOperations`, separate
from task aggregate query. `readTaskResults(...)` and archive streaming read
committed stable-final rows from `TaskResultRuntime`; they do not read
server review materialization. Memory result runtime is volatile
local/dev truth, while Redis result runtime is the cross-process result read
truth.

Owner-backed worker-control and stage-evidence APIs are exposed through SDK
contracts instead of engine internals:

- `WorkerControlOperations` reports worker capability and bounded worker state,
  requests/acknowledges worker commands, and reads command/state snapshots.
- `TaskStageEvidenceOperations` reports task item stage evidence and reads
  bounded stage projections.

These APIs call `WorkerControlService` / `TaskStageEvidenceService` inside the
engine. They do not write public task results, do not treat worker state as
reachability truth, and do not expose engine owner records directly.

Distributed transport v1 splits one engine producer JVM from one or more
transport consumer JVMs without adding server-owned transport endpoints. Use
Redis-backed runtime channels for delivery-command handoff, result ingest, and
delivery-failure compensation. The one-argument
`redisDistributedChannels(redisUri)` helper wires the delivery-command handoff,
result inbox, delivery-failure inbox, Redis route-owner store, Redis
delivery store, and transport-node registry under transport-owned component
namespaces:

| Component | Default namespace |
| --- | --- |
| delivery-command | `xa:mass:transport:delivery-command:v1` |
| result-inbox | `xa:mass:transport:result-inbox:v1` |
| delivery-failure | `xa:mass:transport:delivery-failure:v1` |
| route-owner | `xa:mass:transport:route-owner:v1` |
| delivery | `xa:mass:transport:delivery:v1` |
| nodes | `xa:mass:transport:nodes:v1` |

The two-argument overload is for a caller-owned deployment root and appends the
component names beneath that root. It is not the default namespace shape.

```java
import com.xa.mass.starter.config.TransportRuntimeRole;

String redisUri = "redis://localhost:6379";

MassSdkApplication engineProducer = MassSdk.builder()
        .transport(transport -> transport
                .transportRuntimeRole(TransportRuntimeRole.ENGINE_PRODUCER)
                .redisDistributedChannels(redisUri)
                .webSocketAdapter(webSocket -> webSocket
                        .enabled(false)
                        .serverEnabled(false)))
        .engine(engine -> engine.enabled(true))
        .build();
```

```java
import com.xa.mass.starter.config.TransportRuntimeRole;

String redisUri = "redis://localhost:6379";

MassSdkApplication transportConsumer = MassSdk.builder()
        .transport(transport -> transport
                .transportRuntimeRole(TransportRuntimeRole.TRANSPORT_CONSUMER)
                .transportNodeId("edge-node-a")
                .redisDistributedChannels(redisUri)
                .webSocketAdapter(webSocket -> webSocket
                        .adapterId("ws-public")
                        .server(19090, "/ws")
                        .enabled(true)
                        .serverEnabled(true)))
        .engine(engine -> engine.enabled(false))
        .build();
```

The handoff carries only post-assignment `DeliveryCommand` values translated in
SDK/starter assembly from neutral engine binding truth. In multi-adapter mode
the engine producer resolves delivery feasibility before handoff and writes
bounded delivery-command batches to the target transport node. It is not a
duplicate of the runtime ready queue, and transport consumers must not apply
results, retry tasks, or mutate task lifecycle directly. Result and retryable
delivery-failure inboxes are drained by the engine producer into local
engine-owned ports.

For multi-instance realtime assembly, `adapterType` and `adapterId` are not the
same concept. For example, two bundled WebSocket instances might use adapter ids
such as `ws-public` and `ws-internal`; both still belong to transport hint
`realtime`.

Register SDK project and event resources when the embedding side wants to
expose its own control-plane directory:

```java
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.ProjectDefinition;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.sdk.event.EventDefinition;
import com.xa.mass.base.event.PriorityClass;
import com.xa.mass.base.event.ResponseMode;
import com.xa.mass.base.event.TargetScope;

app.registerEventDefinition(EventDefinition.builder()
        .code("bot.command")
        .name("Bot Command")
        .description("Handle a bot command")
        .payloadTypes(java.util.List.of(PayloadType.JSON))
        .taskModes(java.util.List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
        .priorityClass(PriorityClass.STANDARD)
        .responseMode(ResponseMode.FINAL_RESULT)
        .targetScope(TargetScope.WORKER)
        .build());

app.registerProject(ProjectDefinition.builder()
        .code("botApp")
        .name("Bot App")
        .description("Telegram-style bot project")
        .eventCodes(java.util.List.of("bot.command"))
        .build());

var botTask = app.createTaskShell(MassTaskShellCreateRequest.builder()
        .userId("bot-user")
        .project("botApp")
        .executionSpec(new TaskExecutionSpec())
        .build());

app.appendTaskItems(botTask.getTid(), MassTaskItemBatchAppendRequest.builder()
        .eventCode("bot.command")
        .items(java.util.List.of(
                java.util.Map.of("command", "/start")))
        .build());

app.executeTaskCommand(botTask.getTid(), com.xa.mass.sdk.model.MassTaskCommandRequest.builder()
        .command("SEAL")
        .build());
```

`priorityClass`, `responseMode`, and `targetScope` are event catalog metadata.
They are exposed through SDK/server catalog reads so operators can understand
event behavior, but they do not directly change queue ordering, result
convergence, transport delivery, or worker command routing. Omitted values
default to `STANDARD`, `FINAL_RESULT`, and `WORKER`.

Register a lightweight principal credential binding when an embedding app wants
an API-key or service-account style identity:

```java
app.registerCredentialPrincipal(CredentialPrincipalRegistration.builder()
        .principalId("telegram-bot")
        .credential("dev-api-key")
        .userId("bot-user")
        .projectScope("telegramApp")
        .permissions(java.util.List.of("task:create"))
        .projectScopes(java.util.List.of("telegramApp"))
        .eventScopes(java.util.List.of("bot.command"))
        .attributes(java.util.Map.of("channel", "telegram"))
        .build());

var apiKeyPrincipal = app.authenticateCredential("dev-api-key");
CredentialPrincipalProfile profile = app.getCredentialPrincipal("telegram-bot");
```

`registerCredentialPrincipal(...)` accepts the raw credential.
`listCredentialPrincipals()` and `getCredentialPrincipal(...)` return
`CredentialPrincipalProfile` and intentionally do not expose the credential
back to callers. Registering the same credential for a different principal is
rejected. A single `userId` can own multiple credentials; each credential keeps
its own permissions, project scopes, and event scopes.

The returned `MassSdkApplication` exposes:

- lifecycle: `start()`, `stop()`, `isRunning()`
- mainline task operations after `start()`: `createTaskShell(...)`, `appendTaskItems(taskId, MassTaskItemBatchAppendRequest)`, `executeTaskCommand(taskId, MassTaskCommandRequest)`, `getTaskDetail(...)`, `listTaskSummaries(...)`, `getTaskSummariesByStatus(...)`, `getTaskState(...)`, `getTaskAccess(...)`
- diagnostic-only task state helpers require the explicit `app.taskDiagnostics()` surface; they are not part of the recommended task shell / ingest mainline
- operator/runtime read diagnostics require the explicit `app.runtimeDiagnostics()` surface; they are not part of the task/worker mainline
- raw transport side-channel access remains internal/operator-only below the stable SDK surface; product or server code should not depend on `sdk.internal`
- worker mainline after `start()`: `registerWorker(...)`, `getWorker(...)`, `getAllWorkers()`, `isWorkerReachable(...)`
- worker client/mainline after `start()`: `pullWorker(...)`, `workerOnline(...)`, `workerHeartbeat(...)`, `workerOffline(...)`, `pollTasks(...)`, `submitResult(...)`
- resource/control-plane operations through `ResourceOperations`: `registerProject(...)`, `registerEventDefinition(...)`, `registerCredentialPrincipal(...)`, `listProjects()`, `getProject(...)`, `listEvents()`, `getEvent(...)`, `getEventsForProject(...)`, `listCredentialPrincipals()`, `getCredentialPrincipal(...)`, `authenticateCredential(...)`, `hasProject(...)`, `hasEvent(...)`, `hasCredentialPrincipal(...)`, `projectSupportsEvent(...)`; credential principal list/get return `CredentialPrincipalProfile` without raw credentials
- stable runtime bootstrap surface after `start()`: open mainline registration/mutation methods such as `registerWorker(...)`, `createTaskShell(...)`, `appendTaskItems(taskId, MassTaskItemBatchAppendRequest)`, `executeTaskCommand(taskId, MassTaskCommandRequest)`, `replaceDefaultRules(...)`; WorkerContext registration is no longer an SDK surface
- new bootstrap integration seam: `EngineOptions.bootstrapDataProvider(...)` accepts a pluggable `MassBootstrapDataProvider`

Current SDK contracts:

| Area | Contract |
| --- | --- |
| task create | mainline SDK flow is `MassTaskShellCreateRequest` plus explicit `appendTaskItems(taskId, MassTaskItemBatchAppendRequest)` and `executeTaskCommand(taskId, MassTaskCommandRequest)` for lifecycle/governance; `taskName` is server-derived, and capability `eventCode` belongs on append batches or per-item ingress rather than task shell truth |
| worker resources | `WorkerGroupDeclaration.eventBindings` declares capability truth; `WorkerRegistration` declares worker execution identity plus group/node membership. Transport liveness owns online state, and `isWorkerReachable(...)` reads transport presence when available (`STALE`/`OFFLINE` both surface as not online). WorkerContext registration/snapshot contracts have been removed from the SDK |
| resources | `ResourceOperations` owns project/event resources plus credential-principal projection for embedded runtimes; project is a first-class control-plane binding and enabled projects also bind into engine task creation and worker capability checks. |
| business events | default catalog ships no business task events; embedding apps or dev fixtures register event codes explicitly |
| credential principals | in-memory principal/API-key binding only, not a full user subsystem; queries return `CredentialPrincipalProfile`, not raw credentials |
| diagnostics/detail | bounded runtime validation/resolution stays behind `app.taskDiagnostics()` instead of the default `MassSdkApplication` task mainline. SDK mainline no longer exposes task-item or attempt detail query APIs; production detail belongs in logs, trace, audit sinks, or async persistence |
| removed paths | direct engine/manager/runtime escape hatches are removed; queue/session/raw transport debug methods are also off the stable `MassSdkApplication` main surface |
| startup/bootstrap | operations fail fast without a started engine; mock/demo bootstrap belongs outside SDK via `MassBootstrapDataProvider` / `MassRuntimeControl` |

For embedded runtime wiring, keep the mainline on storage/runtime contracts
such as `taskShellStore(...)`, `taskWorkRuntime(...)`,
`workerDeclarationStore(...)`, and `ruleStorage(...)`. Do not make `TaskManager` or
`WorkerManager` the default SDK assembly surface.
Shell-mainline SDK create maps onto `TaskShellCreateRequestDto`; worker registration/query helpers use `WorkerDeclarationStore`
for control-plane truth instead of treating `WorkerManager` as the default SDK
dependency; SDK rule list/replace helpers now use `RuleStorage` directly
instead of carrying a broad rule manager as the default outer-layer dependency.
Within starter assembly, `EngineConfig` now treats `WorkerManager` and
rule matching contracts as derived helpers over `WorkerDeclarationStore` / `RuleStorage`
rather than independent config slots that outer modules should wire or cache.
Embedded transport runtime assembly also consumes only
`WorkerResourceRuntime` worker resource reads instead of reaching through the
broader worker facade or storage lookup seams.
Assignment no longer hands dispatch-ready batches straight into a transport
routing listener. SDK runtime assembly now translates assignment truth into
`DeliveryCommand` and hands it to `TransportDeliveryCommandHandoff`; the bundled
default is an in-memory bounded queue plus pump, while
`redisDistributedChannels(...)` uses Redis delivery-command inboxes with
node-local drain lanes. Engine/starter assembly resolves `routeKey + adapterId`
and the binding-level selected worker constraint before handoff; transport
consumers drain already resolved delivery targets and do not reselect workers.
The companion result and delivery-failure Redis inboxes are runtime channels
back to the engine process; they are not server APIs and they do not move task
lifecycle ownership into transport.

## Compatibility Policy

`com.xa.mass.sdk.*` is the stable public API surface for this artifact.

`com.xa.mass.starter.*` remains available for advanced embedding at the builder
and facade boundary, but it is a lower-level runtime composition layer and does
not carry the same compatibility commitment as the SDK facade. Transport-owned
runtime internals now live under `com.xa.mass.transport.runtime.*`, and the SDK
default transport composition lives under `com.xa.mass.sdk.transport.*`.

Mock/demo bootstrap behavior is intentionally outside the SDK core. Keep custom
bootstrap code on `MassRuntimeControl`.

The repository CI can enforce binary/source compatibility for `com.xa.mass.sdk.*`
by setting `XA_MASS_SDK_API_BASELINE_VERSION` in GitHub Actions, which wires
through to `-Dmass.sdk.api.baselineVersion=...` and enables the SDK `japicmp`
profile.

## Positioning

Use this module when:

- you are embedding XA Mass Platform into another JVM application
- you want the real embedded runtime plus a stable SDK entry surface in one artifact
- you do not want to pull the demo/control HTTP layer into your dependency graph

Do not treat this module as:

- the Spring Boot runnable entry
- a replacement for `xa-mass-server`
- the HTTP/demo control surface

Current runnable Boot entry remains `xa-mass-server`.

## Internal Runtime Surface

If you need the lower-level runtime composition directly, start here:

- `src/main/java/com/xa/mass/starter/MassApplication.java`
- `src/main/java/com/xa/mass/starter/builder/MassApplicationBuilder.java`
- `src/main/java/com/xa/mass/starter/MassEngine.java`

Treat this lower-level `starter` surface as an advanced embedding path. It remains available, but the default compatibility commitment is on `com.xa.mass.sdk.*`.

Stable builder mainline is `transport(...)`. Bundled WebSocket settings belong
under explicit adapter-owned config such as
`transport(... -> webSocketAdapter(...))`; there is no separate WebSocket-named
builder mainline in `xa-mass-embedded-sdk`.
Additional same-type adapter instances can be appended through
`transport(... -> addWebSocketAdapter(...))` and
`transport(... -> addSocketAdapter(...))`.

Current embedded-runtime mainline snapshots `TransportConfig` into an internal
runtime-composition object during `MassApplication` construction. Runtime
assembly then manages only assembled transport components rather than holding a
live transport config object as the primary composition backbone. That
composition now consumes one or more adapter bootstrap contributions and uses
the transport-neutral `TransportOutboundMessage` outbound carrier keyed by route
rather than a
WebSocket-only delivery DTO.

Within that lower-level surface, embedded-runtime mainline snapshots
`TransportConfig` into `TransportRuntimeComposition`, then uses adapter-owned
bootstrap/contribution assembly for the default WebSocket-backed path or an
explicit `webSocketAdapter(...).transportServerFactory(...)` override.
Adapter bootstrap context now carries only neutral runtime collaborators; shared message
transporter state is kept out of adapter bootstrap inputs entirely. Inbound
server settings such as port/path are owned
by the adapter bootstrap instead of being injected by `MassApplication` at
startup time. Builder-level mainline should configure that bundled adapter
explicitly via `transport(... -> webSocketAdapter(...))` rather than treating
those settings as runtime-global transport facts, and mainline inspection
should read adapter-owned config snapshots instead.
Pre-start worker registration resolution now also comes from adapter-owned
transport descriptors exposed through runtime composition; if a custom worker
transport runtime factory does not expose that metadata, worker registration
must provide explicit `adapterId` before the runtime is started.
Custom primary transport bootstraps are resolved from their own descriptor
metadata rather than from the bundled WebSocket enable flag, so swapping the
primary adapter does not silently erase pre-start registration identity.
Runtime delivery backlog admission is configured through the transport builder;
`maxDeliveryQueuedItems(...)` controls the total queued dispatch cap used by
the resolved delivery store. The embedded mainline still defaults to the
in-memory delivery store, but SDK composition may replace it through
`deliveryStoreFactory(...)` or `redisDeliveryStore(redisUri[, namespacePrefix])`
without changing transport runtime contracts.
`maxDeliveryItemsPerRoute(...)` controls per-route backlog admission for both
the in-memory and Redis-backed delivery stores, so per-worker polling queues
and adapter-local route backpressure can be tuned without changing transport
contracts.
Runtime executor admission is also configurable through
`transportRuntimeMaxPendingTasks(...)` and `eventRuntimeMaxPendingTasks(...)`;
both default to 10000 pending tasks and are reported in executor diagnostics.
SDK control-plane event dispatch is still synchronous by default for
compatibility. Use `transport(... -> eventHandlerTimeoutMillis(...))` to wrap
direct runtime handlers in bounded virtual-thread execution; timeout returns an
`EVENT_TIMEOUT` response and cancellation is cooperative, so handlers should
remain interrupt-aware and use bounded I/O.
Runtime executor diagnostics for transport and optional event-handler execution
are surfaced through the explicit operator/runtime `app.runtimeDiagnostics().getQueueDetail()`
view and the Boot-shell `/api/v1/runtime/queues` response. That HTTP route is
operator/console diagnostics, not an external public SDK contract. Delivery-store diagnostics also expose
`app.runtimeDiagnostics().getQueueDetail().deliveryDiagnostics.queueByAdapter`, which is a legacy
queue-path breakdown name rather than queue ownership truth. RouteKey-owned stores may aggregate this
diagnostic under a route-owner bucket instead of preserving adapter-specific queue identity.
Realtime direct-send counters are intentionally separate under
`app.runtimeDiagnostics().getQueueDetail().deliveryDiagnostics.directByAdapter`; they share delivery outcome
language with queued delivery but they do not imply queue ownership, dequeue,
or durable backlog state.
