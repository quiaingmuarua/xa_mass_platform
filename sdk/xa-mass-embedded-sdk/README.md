# XA Mass Embedded SDK

Status: current embedded SDK runtime-composition owner README.

`xa-mass-embedded-sdk` is the real Java embedding module for XA Mass Platform.

It carries both:

- SDK-facing builder/facade types (`com.xa.mass.starter.*`) that express
  runtime intent and adapter/backend declarations
- the in-process embedding facade (`com.xa.mass.sdk.*`)

The runtime composition has been folded into this artifact so library callers
can depend on one embedded SDK module without pulling the HTTP/demo control surface.
Stable SDK-facing catalog/auth/model contracts now live in the internal
`xa-mass-embedded-sdk-api` module and are pulled transitively through this artifact.
Transport-neutral runtime contracts now live in `xa-mass-transport-api`; the
embedded adapter startup and transport runtime assembly boundary lives behind
`xa-mass-transport-adapter-starter`. The embedded SDK does not directly depend
on concrete transport runtime, polling, socket, or WebSocket implementation
modules; it passes adapter/backend declarations to adapter-starter and keeps
task/result translation in SDK starter code. Migrated task-runtime serving
paths select memory or Redis backends through embedded engine options that
delegate to `xa-mass-task-runtime-starter-sdk`; embedded SDK callers should not
import task-runtime ports, Redis keyspace internals, or old runtime stores.
Runtime stats and active-lease reads exposed through diagnostics are
owner-backed debug projections for the selected serving lane, not a new public
task runtime mutation surface. The embedded SDK exposes task shell, result,
archive, and bounded diagnostic reads through the single `TaskReadOperations`
surface. The interface lives in `xa-mass-embedded-sdk-api`; `MassSdkApplication`
implements it and delegates to the engine-starter read implementation. It
returns SDK-owned snapshots instead of exposing `mass-runtime-api` runtime DTOs.

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

`transportHint` is required for worker registration. External worker/session
callers declare `adapterNodeId + transportHint`; they do not pass concrete
transport runtime ids such as `adapterId`, `routeKey`, `connectionId`, or
endpoint lease ids. Transport assembly resolves adapter/runtime evidence from the
registered adapter-node and runtime configuration. `transportHint` aliases
such as `websocket`, `ws`, `push`, `pull`, or `queue` are not accepted; use
canonical coarse families such as `realtime` or `polling`.

When distributed final-hop delivery needs cross-instance connection evidence,
configure shared transport endpoint leases through
`redisDistributedChannels(...)` or `redisEndpointLeaseStore(...)`. Adapters still own local
session/connect/heartbeat ingress, but shared endpoint lease evidence belongs to
transport runtime as delivery feasibility, not SDK worker inspection or worker
lifecycle truth. `routeKey` remains opaque connection/domain metadata minted
outside transport. Assigned delivery commands carry `deliveryBucketId +
selectedWorkerId`, but physical handoff targets come from worker-runtime
delivery target evidence as opaque adapter mailbox keys. Transport-owned
delivery submitters offer the already assigned item to the adapter-mailbox
delivery queue; command-level `selectedWorkerId` prevents wrong-worker delivery
at drain/final-hop time.
Polling worker responses expose `WorkerAction`
without worker or route metadata because worker identity comes from the
session/path context. Worker replies use `WorkerActionReply.replyRef`; task
result callback correlation remains a starter-owned bridge detail. Worker runtime capacity, lifecycle, and multi-binding
behavior remain owned by worker-runtime scheduling/admission, not by transport
endpoint leasing.

Task result reads are part of `TaskReadOperations`, together with task shell,
access/state, archive, and bounded diagnostic reads. `readTaskResults(...)`
and archive streaming read committed stable-final rows from task-runtime; they
do not read server review materialization. Memory task-runtime remains
local/dev truth, while Redis task-runtime is the cross-process result read
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
Redis-backed runtime channels for dispatch handoff and result ingest. The one-argument
`redisDistributedChannels(redisUri)` helper wires the dispatch handoff,
result ingress queue, Redis endpoint lease store, and Redis
polling pending pull buffer under owner-specific component namespaces:

| Component | Default namespace |
| --- | --- |
| dispatch | `xa:mass:transport:dispatch:v1` |
| result-ingress | `xa:mass:transport:result-ingress:v1` |
| endpoint-lease | `xa:mass:transport:endpoint-lease:v1` |
| polling-delivery | `xa:mass:transport:polling-delivery:v1` |

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
                .redisDistributedChannels(redisUri)
                .webSocketAdapter(webSocket -> webSocket
                        .adapterId("ws-public")
                        .server(19090, "/ws")
                        .enabled(true)
                        .serverEnabled(true)))
        .engine(engine -> engine.enabled(false))
        .build();
```

The handoff carries only post-assignment flat dispatch items translated in
SDK/starter assembly from neutral engine binding truth. Transport-owned delivery
submitters resolve selected-worker adapter mailbox evidence before handoff and
write bounded dispatch batches to the target mailbox. It is not a
duplicate of the runtime ready queue, and transport consumers must not apply
results, retry tasks, or mutate task lifecycle directly. The result ingress
queue is drained by the engine producer into local engine-owned result ports.
Transport no longer maintains a Redis delivery-failure compensation inbox;
accepted dispatches that do not produce a worker result are recovered by
engine-owned attempt timeout/retry.

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
- task write operations after `start()`: `createTaskShell(...)`, `appendTaskItems(taskId, MassTaskItemBatchAppendRequest)`, `executeTaskCommand(taskId, MassTaskCommandRequest)`
- task read operations after `start()`: `getTaskDetail(...)`, `listTaskSummaries(...)`, `getTaskSummariesByStatus(...)`, `getTaskState(...)`, `getTaskAccess(...)`, `readTaskResults(...)`, `getTaskResultArchiveManifest(...)`, `writeTaskResultArchiveContent(...)`, `getTaskWorkStats(...)`, `getActiveLeases(...)`
- operator/runtime read diagnostics require the explicit `app.runtimeDiagnostics()` surface; they are not part of the task/worker mainline
- raw transport side-channel access remains internal/operator-only below the stable SDK surface; product or server code should not depend on `sdk.internal`
- worker mainline after `start()`: `registerWorker(...)`, `getWorker(...)`, `getAllWorkers()`, `isWorkerReachable(...)`
- worker client/mainline after `start()`: `pullWorker(...)`, `workerOnline(...)`, `workerHeartbeat(...)`, `workerOffline(...)`, `pollActions(...)`, `submitActionReply(...)`
- resource/control-plane operations through `ResourceOperations`: `registerProject(...)`, `registerEventDefinition(...)`, `registerCredentialPrincipal(...)`, `listProjects()`, `getProject(...)`, `listEvents()`, `getEvent(...)`, `getEventsForProject(...)`, `listCredentialPrincipals()`, `getCredentialPrincipal(...)`, `authenticateCredential(...)`, `hasProject(...)`, `hasEvent(...)`, `hasCredentialPrincipal(...)`, `projectSupportsEvent(...)`; credential principal list/get return `CredentialPrincipalProfile` without raw credentials
- stable runtime bootstrap surface after `start()`: open mainline registration/mutation methods such as `registerWorker(...)`, `createTaskShell(...)`, `appendTaskItems(taskId, MassTaskItemBatchAppendRequest)`, `executeTaskCommand(taskId, MassTaskCommandRequest)`, `replaceDefaultRules(...)`; WorkerContext registration is no longer an SDK surface
- embedded bootstrap is explicit through the normal engine store/runtime options; mock/demo bootstrap is not a separate SDK-owned data-provider lane

Current SDK contracts:

| Area | Contract |
| --- | --- |
| task create | mainline SDK flow is `MassTaskShellCreateRequest` plus explicit `appendTaskItems(taskId, MassTaskItemBatchAppendRequest)` and `executeTaskCommand(taskId, MassTaskCommandRequest)` for lifecycle/governance; `taskName` is server-derived, and capability `eventCode` belongs on append batches or per-item ingress rather than task shell truth |
| worker resources | `WorkerGroupDeclaration.eventBindings` declares capability truth; `WorkerRegistration` declares worker execution identity plus group/node membership. `isWorkerReachable(...)` reports worker runtime lifecycle availability and does not read selected-worker transport owner evidence. WorkerContext registration/snapshot contracts have been removed from the SDK |
| resources | `ResourceOperations` owns project/event resources plus credential-principal projection for embedded runtimes; project is a first-class control-plane binding and enabled projects also bind into engine task creation and worker capability checks. |
| business events | default catalog ships no business task events; embedding apps or dev fixtures register event codes explicitly |
| credential principals | in-memory principal/API-key binding only, not a full user subsystem; queries return `CredentialPrincipalProfile`, not raw credentials |
| diagnostics/detail | bounded runtime validation/resolution and runtime stats are read-only methods on `TaskReadOperations`. SDK mainline no longer exposes task-item or attempt detail query APIs; production detail belongs in logs, trace, audit sinks, or async persistence |
| removed paths | direct engine/manager/runtime escape hatches are removed; queue/session/raw transport debug methods are also off the stable `MassSdkApplication` main surface |
| startup/bootstrap | operations fail fast without a started engine; mock/demo bootstrap belongs outside SDK public engine assembly |

For embedded runtime wiring, keep the mainline on storage/runtime contracts
such as `taskShellStore(...)`, `taskWorkRuntime(...)`,
`workerDeclarationStore(...)`, and `ruleStorage(...)`. Do not make `TaskManager` or
`WorkerManager` the default SDK assembly surface.
For migrated task item serving paths, select the task-runtime backend through
`memoryTaskRuntime()` or `redisTaskRuntime(redisUri, namespace)`; do not inject
task-runtime ports or Redis task-runtime keyspace details through SDK callers.
Shell-mainline SDK create maps onto `TaskShellCreateRequestDto`; worker registration/query helpers use `WorkerDeclarationStore`
for control-plane truth instead of treating `WorkerManager` as the default SDK
dependency; SDK rule list/replace helpers now use `RuleStorage` directly
instead of carrying a broad rule manager as the default outer-layer dependency.
Within starter assembly, `EngineConfig` now treats `WorkerManager` and
rule matching contracts as derived helpers over `WorkerDeclarationStore` / `RuleStorage`
rather than independent config slots that outer modules should wire or cache.
Embedded transport runtime assembly also consumes only
the narrow worker resource query/transport registry ports it needs instead of
reaching through a broad worker facade or storage lookup seams.
Assignment no longer hands dispatch-ready batches straight into a transport
routing listener. SDK runtime assembly now translates assignment truth into
flat dispatch items and hands them to a transport-owned selected-worker
delivery submitter; the bundled default is an in-memory bounded dispatch
handoff, while `redisDistributedChannels(...)` uses a Redis dispatch handoff with a
mailbox-scoped delivery queue. Starter assembly records only `deliveryBucketId +
selectedWorkerId` plus typed payload/context, then resolves the selected worker
through worker-runtime delivery target evidence to an opaque
`adapterMailboxKey`. Adapter, route, connection, and endpoint facts remain
transport-owned evidence. Transport producers do not derive queues from
`deliveryBucketId` and do not resolve transport nodes. Transport consumers drain
mailbox entries, demux by item-level `selectedWorkerId`, invoke the local
adapter final-hop executor, and do not reselect workers.
The companion result ingress queue is the runtime channel back to the engine
process; it is not a server API and it does not move task lifecycle ownership
into transport.

## Compatibility Policy

`com.xa.mass.sdk.*` is the stable public API surface for this artifact.

`com.xa.mass.starter.*` remains available for advanced embedding at the builder
and facade boundary, but it is a lower-level runtime composition layer and does
not carry the same compatibility commitment as the SDK facade. Transport-owned
runtime internals are assembled behind `xa-mass-transport-adapter-starter`; SDK
main source should not import transport runtime or concrete adapter
implementation packages directly.

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

Current embedded-runtime mainline keeps `TransportConfig` as the SDK-local
configuration snapshot and declaration source. It contains adapter-starter
declarations rather than runtime stores, queues, factories, or concrete adapter
configs. `MassApplication` creates a single adapter-starter
`EmbeddedTransportAssembly`; that assembly owns transport queue/result/lease
construction, adapter runtime creation, adapter start/close, registration
descriptor resolution, binding lookup, and pull-worker transport resolution.

Task dispatch translation remains SDK starter-owned: engine assignment truth is
encoded into adapter-starter `AssignedDeliveryMessage` values and submitted
through an `AssignedDeliverySink`. Result convergence remains SDK
starter-owned: `TaskResultIngressQueueDrain` reads an adapter-starter
`ResultIngressSource` and passes entries to engine result ingest. The adapter
starter does not interpret task lifecycle, worker selection, or engine result
policy.

Builder-level mainline should configure bundled adapters explicitly via
`transport(... -> webSocketAdapter(...))` or
`transport(... -> socketAdapter(...))`; these populate adapter-starter-owned
declarations, not concrete WebSocket/Socket config objects.

Polling pull admission is configured through the transport builder;
`maxPollingPendingDeliveryItems(...)` controls the total queued polling pull
backlog. The embedded mainline defaults to adapter-starter-owned in-memory
transport primitives, and SDK composition may request Redis-backed polling
delivery through `redisPollingDeliveryQueue(redisUri[, namespacePrefix])`
without exposing polling buffer implementation types.
`maxPollingPendingDeliveryItemsPerWorker(...)` controls per-worker polling
pending backlog admission for both the in-memory and Redis-backed polling
buffers.
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
operator/console diagnostics, not an external public SDK contract. Default
worker/session inspection and public worker APIs must not expose transport
internal ids such as `adapterId`, `routeKey`, `connectionId`, endpoint lease
ids, or `deliveryQueueKey` as delivery targets. `runtimeDiagnostics()` does not
provide session or endpoint get-all inventories; worker reachability and lock
labels should come from worker/admission owners for the selected subjects. If a future
operator-only detail endpoint needs raw transport ids, it must be a bounded
diagnostic surface and explicitly documented as non-contractual. Delivery-store diagnostics also expose
`app.runtimeDiagnostics().getQueueDetail().deliveryDiagnostics.queueByAdapter`, which is a legacy
queue-path breakdown name rather than queue ownership truth. RouteKey-owned stores may aggregate this
diagnostic under an endpoint-lease bucket instead of preserving adapter-specific queue identity.
Realtime push final-hop outcomes are produced by concrete adapter executors and
are not folded into delivery queue diagnostics.
