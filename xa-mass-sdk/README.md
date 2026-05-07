# XA Mass SDK

Status: current SDK owner README.

`xa-mass-sdk` is the real Java embedding module for XA Mass Platform.

It carries both:

- the embedded runtime composition, split between SDK-facing builder/facade types (`com.xa.mass.starter.*`), SDK-owned transport composition (`com.xa.mass.sdk.transport.*`), and shared transport runtime assembly (`com.xa.mass.transport.runtime.*`)
- the consumer-facing SDK facade (`com.xa.mass.sdk.*`)

The runtime composition has been folded into this artifact so library callers
can depend on one SDK module without pulling the HTTP/demo control surface.
Stable SDK-facing catalog/auth/model contracts now live in the internal
`xa-mass-sdk-api` module and are pulled transitively through this artifact.
Transport-neutral runtime contracts now live in `xa-mass-transport-api`; the
current bundled transport adapters include polling plus realtime adapters such
as WebSocket and socket. Adapter/bootstrap ownership lives in adapter modules
such as `xa-mass-transport-websocket` and `xa-mass-transport-socket`.
`xa-mass-sdk` assembles worker transports through a transport runtime
registry/factory seam instead of hiding a websocket-first default runtime.

## Dependency

```xml
<dependency>
  <groupId>com.xa.mass</groupId>
  <artifactId>xa-mass-sdk</artifactId>
  <version>${xa.mass.version}</version>
</dependency>
```

## Quick Start

Create an SDK application handle:

```java
import com.xa.mass.sdk.MassSdk;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.auth.SubmitterMetadata;
import com.xa.mass.sdk.auth.SubmitterRegistration;
import com.xa.mass.sdk.model.MassTaskCreateRequest;
import com.xa.mass.sdk.model.MassTaskRequest;
import com.xa.mass.sdk.model.WorkerEventBinding;
import com.xa.mass.sdk.model.WorkerContextRegistration;
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

app.registerWorker(WorkerRegistration.builder()
        .workerId("crawler-worker-1")
        .workerGroupId("crawler")
        .supportedProjects(java.util.List.of("demoApp"))
        .eventBindings(java.util.List.of(
                WorkerEventBinding.builder()
                        .eventCode("demo.dispatch")
                        .projectCodes(java.util.List.of("demoApp"))
                        .build()
        ))
        .transportHint("polling")
        .attributes(java.util.Map.of("type", "crawler"))
        .build());
app.registerWorkerContext(WorkerContextRegistration.builder()
        .workerContextId("ctx-crawler-worker-1")
        .workerId("crawler-worker-1")
        .project("demoApp")
        .routingTags(java.util.Set.of("us"))
        .attributes(java.util.Map.of("region", "us"))
        .build());

app.createTask(MassTaskCreateRequest.builder()
        .userId("agent")
        .project("demoApp")
        .taskName("demo-task")
        .sharedConfig(java.util.Map.of("textContent", "hello", "routingCode", "us"))
        .inputs(java.util.List.of(java.util.Map.of("target", "target-a")))
        .batchSize(1)
        .build());

app.pullWorker("crawler-worker-1").connect();
```

`supportedProjects` is only a coarse worker grouping/filter hint. New worker capability registration should declare `eventBindings`; when `eventBindings` is present it becomes the worker capability truth and SDK registration derives `supportedEventCodes` / `supportedProjects` from it.

`transportHint` is required for worker registration, and `adapterId` is the concrete runtime identity. Registration resolution now comes from transport runtime metadata rather than SDK-side `realtime -> websocket` guessing. Realtime workers must always register with explicit `adapterId + transportHint`; only polling keeps the implicit family default to `polling`. `pullWorker(...)` also resolves strictly from the worker's declared transport identity and fails fast on transport mismatch instead of falling back to another pull-capable adapter. Adapter-id aliases such as `ws`, `pull`, `queue`, or `tcp-socket` are not accepted as runtime identities; use canonical adapter ids such as `websocket`, `polling`, or `socket`. `transportHint` aliases such as `websocket`, `ws`, `push`, `pull`, or `queue` are also not accepted; use canonical coarse families such as `realtime` or `polling`. Adapter implementation labels such as `WorkerAdapter.protocol()` are no longer treated as runtime transport truth; selection keys off canonical registration identity instead.

For multi-instance realtime assembly, `adapterType` and `adapterId` are not the
same concept. For example, two bundled WebSocket instances might use adapter ids
such as `ws-public` and `ws-internal`; both still belong to transport hint
`realtime`.

Register SDK catalog metadata when the embedding side wants to expose its own
project/event directory:

```java
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.ProjectMetadata;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.sdk.event.EventDefinition;

app.registerEventDefinition(EventDefinition.builder()
        .code("bot.command")
        .name("Bot Command")
        .description("Handle a bot command")
        .payloadTypes(java.util.List.of(PayloadType.TEXT, PayloadType.JSON))
        .taskModes(java.util.List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
        .build());

app.registerProject(ProjectMetadata.builder()
        .code("botApp")
        .name("Bot App")
        .description("Telegram-style bot project")
        .eventCodes(java.util.List.of("bot.command"))
        .build());

app.createTask(MassTaskRequest.singleRun("botApp", "bot-command")
        .eventCode("bot.command")
        .textInputs(java.util.List.of("/start"))
        .build());
```

Register a lightweight principal credential binding when an embedding app wants
an API-key or service-account style identity:

```java
app.registerSubmitter(SubmitterRegistration.builder()
        .principalId("telegram-bot")
        .credential("dev-api-key")
        .userId("bot-user")
        .projectScope("telegramApp")
        .permissions(java.util.List.of("task:create"))
        .projectScopes(java.util.List.of("telegramApp"))
        .eventScopes(java.util.List.of("bot.command"))
        .attributes(java.util.Map.of("channel", "telegram"))
        .build());

var submitter = app.authenticateSubmitter("dev-api-key");
SubmitterMetadata metadata = app.getSubmitter("telegram-bot");
```

`registerSubmitter(...)` accepts the raw credential. `listSubmitters()` and
`getSubmitter(...)` return `SubmitterMetadata` and intentionally do not expose
the credential back to callers. Registering the same credential for a different
principal is rejected. A single `userId` can own multiple credentials; each
credential keeps its own permissions, project scopes, and event scopes.

The returned `MassSdkApplication` exposes:

- lifecycle: `start()`, `stop()`, `isRunning()`
- common task operations after `start()`: `createTask(MassTaskCreateRequest)`, `createTask(MassTaskRequest)`, `getTask(...)`, `getAllTasks()`, `getTasksByStatus(...)`, `approveTask(...)`, `rejectTask(...)`, `blockTask(...)`, `pauseTask(...)`, `resumeTask(...)`, `resumeTaskDetailed(...)`, `cancelTask(...)`, `terminateTask(...)`
- open-ended task operations after `start()`: `appendTaskItems(...)`, `sealTask(...)`
- audit and compatibility diagnostics after `start()`: bounded `getTaskMessageSnapshot(..., limit)`, `resolveTaskState(...)`, `validateTaskState(...)`, `auditTaskProjectionState(...)` (`validateTaskState(...)` stays bounded runtime validation; `auditTaskProjectionState(...)` is the explicit deep compatibility-projection audit)
- explicit compatibility detail after `start()`: `getTaskMessageView(...)`, `getTaskMessageAttemptViews(...)`, `getLatestActiveTaskMessageAttemptView(...)`
- common worker operations after `start()`: `registerWorker(...)`, `registerWorkerContext(...)`, `getWorker(...)`, `getAllWorkers()`, `getAllWorkerContexts()`, `getWorkerContexts(...)`, `getWorkerContextById(...)`, `isWorkerLocked(...)`, `isWorkerOnline(...)`
- resource/control-plane operations through `ResourceOperations`: `registerProject(...)`, `registerEventDefinition(...)`, `registerSubmitter(...)`, `listProjects()`, `getProject(...)`, `listEvents()`, `getEvent(...)`, `getEventsForProject(...)`, `listSubmitters()`, `getSubmitter(...)`, `authenticateSubmitter(...)`, `hasProject(...)`, `hasEvent(...)`, `hasSubmitter(...)`, `projectSupportsEvent(...)`; submitter list/get return `SubmitterMetadata` without credentials
- pull-style worker entry after `start()`: `pullWorker(...)`
- stable runtime bootstrap surface after `start()`: open registration methods such as `registerWorker(...)`, `registerWorkerContext(...)`, `createTask(...)`, `replaceDefaultRules(...)`
- new bootstrap integration seam: `EngineOptions.bootstrapDataProvider(...)` accepts a pluggable `MassBootstrapDataProvider`

Current SDK contracts:

| Area | Contract |
| --- | --- |
| task create | `MassTaskCreateRequest` is generic compatibility create; `MassTaskRequest` is SDK v1 for `single-run` / `streaming`, `text` / `json`, and event-aware creation |
| worker resources | `WorkerRegistration` / `WorkerContextRegistration` declare identity/capability only; workers start `OFFLINE`, contexts `IDLE`; transport liveness owns online state |
| resources | `ResourceOperations` owns project/event/submitter resources; enabled projects also bind into engine task creation and worker-context project checks |
| business events | default catalog ships no business task events; embedding apps or dev fixtures register event codes explicitly |
| submitters | in-memory principal/API-key binding only, not a full user subsystem; queries return `SubmitterMetadata`, not credentials |
| diagnostics/detail | `validateTaskState(...)` is bounded runtime validation, `auditTaskProjectionState(...)` is explicit diagnostic audit, `getTaskMessageSnapshot(..., limit)` is bounded compatibility/demo detail, and `getTaskMessageView(...)` / `getTaskMessageAttemptViews(...)` are explicit SDK-owned residue views; production detail belongs in logs, trace, audit sinks, or async persistence |
| removed paths | direct engine/manager/runtime escape hatches are removed; default path is `MassSdkApplication` |
| startup/bootstrap | operations fail fast without a started engine; mock/demo bootstrap belongs outside SDK via `MassBootstrapDataProvider` / `MassRuntimeControl` |

For embedded runtime wiring, keep the mainline on storage/runtime contracts
such as `taskStorage(...)`, `taskDetailStore(...)`, `taskWorkRuntime(...)`,
`workerStorage(...)`, and `ruleStorage(...)`. Do not make `TaskManager` or
`WorkerManager` the default SDK assembly surface.
SDK-internal task creation now maps onto the neutral base
`TaskCreateRequestDto`; worker registration/query helpers use `WorkerStorage`
for control-plane truth instead of treating `WorkerManager` as the default SDK
dependency; SDK rule list/replace helpers now use `RuleStorage` directly
instead of carrying `RuleManager` as the default outer-layer dependency.
Within starter assembly, `EngineConfig` now treats `WorkerManager` and
`RuleManager` as derived helpers over `WorkerStorage` / `RuleStorage` rather
than independent config slots that outer modules should wire or cache.
Embedded transport runtime assembly also consumes only
`WorkerLookupStore`-level worker resolution instead of reaching through the
broader worker facade.
Assignment no longer hands dispatch-ready batches straight into the transport
routing listener. SDK runtime assembly now inserts an explicit
`TaskDispatchHandoff` seam between engine and transport; the bundled default is
still an in-memory queue plus pump, but the replacement boundary is now
explicit for future durable or cross-node runtime wiring.

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
builder mainline in `xa-mass-sdk`.
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
are surfaced through `getQueueDetail().runtimeExecutors` and the Boot-shell
`/api/queue/detail` response. Delivery-store diagnostics also expose
`getQueueDetail().deliveryDiagnostics.queueByAdapter`, which is the adapter-neutral
per-`adapterId` queue breakdown intended to survive a later Redis/JDBC store
replacement. Realtime direct-send counters are intentionally separate under
`getQueueDetail().deliveryDiagnostics.directByAdapter`; they share delivery outcome
language with queued delivery but they do not imply queue ownership, dequeue,
or durable backlog state.
