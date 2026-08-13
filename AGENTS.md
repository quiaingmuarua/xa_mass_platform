# XA Mass Kernel Agent Handoff

Status: current repository handoff.

## Mainline

- `kernel_design/` is the current mechanism oracle.
- `kernel_jvm/` is the JVM parity surface for Kernel owner contracts and
  selected owner-specific Redis providers. It does not implement scheduling,
  Pacers, or the Kernel application lifecycle. Its Worker score provider is
  deliberately limited to get/initialize used by `WorkerRuntime.upsertWorker`
  plus a parity-proved reconcile mechanism with no production caller; all
  scheduling score operations remain gaps.
- `server_jvm/` is the external Runtime API process. Controllers and services
  depend on `kernel_jvm` owner contracts. Its assembly binds Task control
  operations to Python HTTP providers and Worker resource, Task data, and
  delivery operations to Java Redis providers; it does not define a second set
  of Kernel runtime ports. It may
  initialize advisory WorkerGroup catalog metadata, start configured Adapters,
  and pass an opaque Scenario Group manifest, the fixed local Lab root, and the
  public Runtime API base URL to the Scenario Worker module. It does not parse
  Lab Worker files, implement Scenario handlers, or own individual Worker
  resource lifecycle.
- `scenario_workers_jvm/` is the Java 21 finite Scenario Worker assembly. It
  owns the checked-in phone-number and string-utility event definitions,
  strict configured-Group directory discovery, per-Group initialization based
  only on directory existence, one persistent JSON per Lab Worker, real
  WebSocket Worker construction, public-HTTP identity registration, explicit
  best-effort Property Index updates, and aggregate resource cleanup. Its
  aggregate start does not wait for an initial WebSocket connection. It is not
  a Kernel owner, Server profile, Adapter, plugin SPI, or independently
  deployed application.
- `worker_delivery_contract_jvm/` is the Java 11 compatible transport-neutral
  DeliveryCommand/DeliveryReport contract shared by Server, Adapter, and Worker.
  DeliveryReport carries required producer `src + sourceId`; DeliveryCommand
  target identity remains outside the DTO. Neither DTO carries an outer
  `messageId`; `forward` remains opaque until its owning downstream mechanism.
  Long-lived transports first send an
  Adapter-directed identity Report and
  then exchange direct command/result JSON; there is no third connection DTO
  or generic connection-message envelope.
- `transport/netty-adapter/` owns complete Adapter instances. Server constructs
  the finite WebSocket/Socket choices through one public factory returning
  `WorkerDeliveryAdapter`. Each endpoint has one package-private Adapter
  aggregate for lifecycle and network ordering; one
  `AdapterProcessManager` for the finite Process set, its single scheduler,
  phase-local quiescence, and reverse finishing; one `DeliveryCommandProcess` for
  Command consumption, delivery, and expiry; one `DeliveryReportProcess` for
  Result acceptance, pending-batch retry, and remote ingress; one private
  soft-capacity `FiniteQueue` owned by each Process; three owner-local Remote
  APIs for Command consumption, Report ingress, and route verification; one
  Adapter-private concrete HTTP client shared only by those Remote APIs;
  connection mechanism plus route Registry for identity, first verification,
  Command routing, and Result ingress; and one complete WebSocket or Socket
  physical Server for listener, EventLoop, every child Channel, full Pipeline,
  writes, and close behavior. The common mechanism derives connection phase
  from instance-local Registry truth and never performs physical Channel
  operations. There is no public protocol SPI,
  abstract Adapter base, transport-kind runtime, shared mutable state, or fat
  connection Session. Cross-package Netty collaborators are classified under
  `netty.internal.process`, `netty.internal.connection`,
  `netty.internal.remote`, and `netty.internal.network`; they are
  repository-internal even when Java
  visibility must be public. Server may depend only on the finite factory.
  This three-owner production cut is frozen. WebSocket and Socket preserve
  complete physical ownership and are aligned by a test-only behavior contract,
  not a shared lifecycle implementation.
  Adapter-directed payloads are consumed locally;
  only bound TASK results with Worker-owned outcomes enter the Result queue,
  preserving their original JSON.
  It has no Spring, Server, Kernel, or Redis dependency.
- `transport/worker-core/` is the Java 11 local core containing Worker
  execution, event definitions, error classification, Worker
  Polling, `WorkerPreparation`, the two-state `WorkerRunController`, single-run
  text-message Transport, synchronous Command dispatch, and network
  Client contracts. It also owns the shared `WorkerLifecycle`, Identity store,
  Properties provider, platform-neutral Register/Bind control contracts,
  Client factory, immutable reconnect policy, and the threadless reconnect
  helper still consumed by the Java line-Socket Client.
  The Client owns concrete networking and transparent reconnect, the Transport
  owns identity/Command/Result protocol, and `WorkerRunController` owns only the
  `RUNNING/STOPPED` run lifecycle. Core submits lifecycle work to a
  platform-injected Control Executor but creates and closes no thread, Executor,
  or Scheduler and contains no concrete network or platform implementation.
- `transport/java-worker/` owns the `JavaWorker` WebSocket/line-Socket assembly,
  the fixed-WorkerGroup `JavaWorkerManager`, package-private Platform owners,
  plus the default Java OkHttp
  point/WebSocket/control and JDK line-socket Client implementations. One
  Manager runs a configuration-fixed replica set for exactly one WorkerGroup
  and shares one internal Platform only among those replicas. Different
  Managers own independent resources and reconcile only when explicitly
  invoked. Neither is a second Worker state-machine owner, CLI,
  application, Android wrapper, automatic restart scheduler, or business
  handler collection.
- `transport/android-worker/` is an internal Android Library containing the
  Host-Looper OkHttp WebSocket Client, Android Register/Bind Client, long-lived
  Identity storage, and the complete `AndroidWorker` assembly. It
  implements Core's `WorkerLifecycle`, delegates its mechanism to the shared
  Worker Run Controller, and does not persist Endpoint URIs or implement a
  second command, result, session, or business-message cache.
- `integrations/worker-capability-rpc/` is a Java 21 external acceptance
  assembly. Its lightweight `RpcProcess` accepts caller-supplied string lines,
  parses each line once into a Payload, combines it with an explicitly assembled
  finite event list, and performs bounded concurrent calls through the public
  WorkerGroup Runtime API. It returns ordered in-memory results; validation and
  JSONL output are optional batch middleware of the checked-in scenario. It is
  not a Task or Worker owner, file-processing framework, plugin SPI, retry
  engine, persistent queue, or chained-request scheduler.
- The legacy Java platform is available exclusively from
  `legacy-java-platform-final-2026-07-24`.
- There is no compatibility obligation to legacy Java APIs, modules, Redis
  shapes, SDKs, server routes, transport contracts, or frontend models.

## Trust Order

1. `kernel_design/executable_spec/` code and tests.
2. Verified Redis behavior.
3. Current `kernel_design/doc/` mechanism contracts.
4. `kernel_design/README.md` and `kernel_design/AGENTS.md`.
5. Historical tag material only as failure-mode evidence.

If executable code and a current mechanism document disagree, identify the
drift before changing either one. Do not infer new behavior from the legacy
tag.

## Working Rules

- Scope mechanism searches, diffs, and Python tests to `kernel_design/`.
- Preserve explicit owner boundaries across core contracts, scheduling,
  Redis implementations, assembly, and external protocol examples.
- Treat every new Kernel owner operation as a long-lived cost commitment.
  Prefer owner-local operations with explicit caller-bounded identities and
  same-key aggregation; policy richness does not justify a broad owner API.
- Keep cross-key fan-out, global discovery, owner-spanning aggregation, and
  background coordination in the caller or system policy by default. A
  high-cost contract addition requires a named invariant, worst-case bound,
  failure semantics, rejection of cheaper composition, and focused proof.
- Keep each JVM module's coded exception local to its owner. Do not create a
  cross-module exception base or move runtime exceptions into a wire contract.
  Keep exceptions to `errorCode + owner.method operation + message + cause`.
  Complete execution context belongs at log and tracing call sites.
- Use JDK `System.Logger` directly in JVM-only modules. Android-consumed Java
  modules use `java.util.logging` because Android 13 does not provide
  `System.Logger`. Log numeric owner error codes, operation, and safe
  identifiers, never opaque Worker payload or result context.
- Do not add bridges, compatibility aliases, mirrored DTOs, or speculative
  modules.
- Keep score values opaque outside their owner operations.
- Use real Redis proof for Redis behavior and concurrency claims.
- Update the owning mechanism document when behavior changes.
- Do not implement Kotlin behavior until a scoped parity slice names the
  Python contract and proof it replaces.
- Keep Server controllers and use-case services on `kernel_jvm` owner
  contracts. Provider selection belongs only to Server assembly; do not let
  controllers depend on Python transport or owner Redis implementations.
- Python HTTP owner adapters may implement only the operations exposed by the
  current Python command host. They must convert wire DTOs to Kernel DTOs and
  must not provide fallback for an unimplemented operation.
- Java Redis owner operations belong in the matching `kernel_jvm` owner
  package. `server_jvm.kernelredis` owns only connection and health. Java must
  not read or mutate Task score, candidate cache, Pacers, or ResultRouting
  consumption. The only Java Worker score operations currently allowed inside
  Worker upsert are get/initialize. Properties are replaced by the same upsert
  without changing an existing score. Reconcile is parity-proved but has no
  production caller and must not be treated as resource-upsert behavior.
- Missing JVM owner operations must fail with
  `KernelOperationNotImplementedException`; do not hide gaps with default
  methods, compatibility clients, or remote fallback.
- Keep `worker_delivery_contract_jvm` transport-neutral. Worker Delivery HTTP
  access and the Kernel delivery owner facade live in `server_jvm`. The
  Adapter instances may reach that facade only through the Adapter batch HTTP
  contract.
- `transport/netty-adapter` must not depend on `server_jvm`, `kernel_jvm`,
  Spring, Redis, scores, Pacers, or Server HTTP DTOs. The Adapter module owns
  its complete instances through three frozen Netty-specific layers. The
  `NettyWorkerDeliveryAdapter` aggregate owns lifecycle and network shutdown
  ordering. Its `AdapterProcessManager` owns the finite
  `List<ScheduledAdapterProcess>`, one same-lifetime scheduler, per-Process
  round isolation, phase-local quiescence, and reverse finish order. It stores
  no per-Process Future and exposes no individual Process stop operation.
  `DeliveryCommandProcess` owns its one private Command `FiniteQueue`, remote
  Command acquisition, expiry, and rotation; it calls the concrete connection
  and Report owners directly. `DeliveryReportProcess` owns its one private
  Result `FiniteQueue`, explicit `ingress(...)` operation, pending batch, and
  remote Result submission.
  `FiniteQueue` is thread-safe business-neutral Process infrastructure and is
  never passed between owners. `DeliveryCommandRemoteApi`,
  `DeliveryReportRemoteApi`, and `WorkerRouteRemoteApi` own their respective
  paths, wire JSON, expected status, and failure classification. One
  Adapter-private `WorkerDeliveryHttpClient` owns only JDK HTTP resources, base
  URI, timeout, headers, path encoding, expected-status enforcement, and raw
  request mechanics; it imports no Delivery DTO or owner HTTP codec. Process
  and connection owners never receive that Client, a URL, an HTTP status, or
  an HTTP JSON contract. One shared
  `WorkerConnectionMechanism` owns strict identity interpretation,
  first-seen route verification, Command routing, and Result ingress, while
  its `WorkerRouteRegistry` owns the verified worker-ID set,
  pending-verification map, `workerId -> current Channel` truth, and
  Channel-to-worker correlation. A complete `WebSocketNettyWorkerServer` or
  `SocketNettyWorkerServer` owns the listener, EventLoop, every child Channel,
  physical framing, writes, asynchronous write failure, and protocol close
  mapping. The shared mechanism exposes concrete `deliver(...)`, depends only
  on its route Remote API and concrete Report Process, and receives normalized
  `String` values. It may
  retain `Channel` only as an address; it must return every physical write or
  close to the selected Server. Connection phase is derived from Registry
  truth rather than a phase enum, Session, or Pipeline Handler replacement.
  Adapter instances share implementation but no route state or Channel
  registry. Verification
  success is cached only for that Adapter process. Ordinary disconnect removes
  the exact active Channel but retains verified identity; Adapter close/restart
  clears verified, pending, and active state. This cache is not persistent
  Binding, authentication, Worker online truth, or an implicit unbind mechanism.
  Input after identity while first verification is pending is dropped, not
  buffered or used to close the Channel. There is no Adapter event registry or
  plugin dispatcher. Only verified TASK Reports declaring
  `src=WORKER`, the bound workerId, and `200` or Worker-owned `3...` may enter
  the Result queue, with encoded JSON, payload, and forward context unchanged.
  SYSTEM and invalid bound input are logged and
  dropped; invalid unbound input and a full or closed Result queue physically
  close the Channel. Physical close is reconnectable network evidence; only
  `ADAPTER/worker.connection.close` terminates the current Worker run.
  Adapter-owned outcomes must use `WorkerDeliveryAdapterErrorCode`. The private
  owner-local HTTP contracts are
  proved against Server JSON with bilateral golden tests; do not add an
  in-process fast path. `shutdownTimeout` is separately budgeted by each
  physical Server and by the Adapter Process scheduler; no close path may reset a
  spent deadline or use an unbounded wait. Normal close may additionally spend
  one HTTP request timeout on its best-effort final Result flush. If the
  scheduler misses its budget, stop Result acceptance, skip the contended
  final flush, finish other cleanup, and report Adapter error `21004`.
- `transport/worker-core` may depend only on the shared Worker Delivery
  contract. It must compile with `--release 11` and must not import OkHttp,
  Android, Netty, Spring, Redis, Server, or Kernel implementations.
  `RegisteredWorkerPreparation` owns one-time Properties loading, validation,
  defensive copying, Worker ID recovery, and Register/Bind.
  `WorkerRunController` owns one two-state run,
  one asynchronously submitted Preparation call, the current Transport,
  cooperative stop, and local lifecycle observation. Core may submit to its
  injected Control Executor but must not create or shut down threads,
  Executors, or Schedulers. Lifecycle
  listeners are synchronous, lightweight level
  observations invoked outside the lifecycle state lock; `snapshot()` is
  authoritative and notifications may repeat. `WorkerRunController` does not
  accept or route Worker commands. The single-run Transport owns inbound
  Adapter identity Report emission, DeliveryCommand decoding, synchronous
  `WorkerCommandExecutor` invocation, conversion of `WorkerCommandOutcome` to
  `DeliveryReport.fromCommand(WORKER, workerId, ...)`, one-shot Report send, direct
  `ADAPTER/worker.connection.close` termination without a Result, and
  exact-once terminal notification to `WorkerRunController`. It creates no
  Command queue, execution task, in-flight registry, or Result cache. One
  Client's ordered callback lane serializes its Commands; separate Client
  connections may execute shared thread-safe Definitions concurrently. A
  terminal Transport waits for the current callback, discards its late Result,
  and exposes no Bind, execution, reconnect, or exit-in-progress state to the
  lifecycle layer.
  `WorkerCommandDispatcher.forWorker` exclusively composes the effective
  immutable registry from Core built-ins followed by Host Definition
  extensions. The Core built-in set is currently empty; connection-close is a
  Transport lifecycle instruction, not a business Definition. Duplicate
  `(src,eventCode)` keys fail assembly; Transports receive an already assembled
  `WorkerCommandExecutor` and must not accept Definition collections.
  `TextMessageClient` alone owns transient
  disconnect/failure handling and reconnect scheduling; its Transport listener
  exposes only open, inbound message, and exact-once endpoint termination.
  It exposes no physical connection query or reconnect event; `send()` is the
  Transport's only evidence that the current frame was accepted for sending.
  Preparation failure or endpoint termination ends the current Worker run;
  Core neither retries Preparation nor schedules another start. Only a later
  explicit Host `start()` may prepare another run. `WorkerLifecycle`, `JavaWorker`, and
  `AndroidWorker` expose only the two-state lifecycle snapshot and must not
  expose connection state, a local DeliveryCommand injection method, or cached
  Worker business messages.
- `transport/java-worker` may depend on `transport/worker-core`, the shared
  Worker Delivery contract, OkHttp, and JDK networking. It must compile with
  `--release 11`, expose no OkHttp types, and must not import Android, JNDI,
  Server, Kernel, Redis, platform business handlers, score, Pacer, or TaskType.
  Standalone `JavaWorker.create(...)` creates a package-private Platform but
  performs no Control or connection I/O before `start()`;
  `start()` and `stop()` submit non-blocking requests, and closing a
  JavaWorker closes its Controller before its Platform. The Platform owns one
  OkHttp base client, one WebSocket scheduler, bounded Socket and Control
  pools, and no Command executor. OkHttp callbacks invoke the Transport and
  Handler synchronously through a per-Client serialization gate; the shared
  WebSocket scheduler owns only connect/reconnect timing.
  `JavaWorkerManager.Builder`
  binds one WorkerGroup's shared
  capacity and a fixed, non-empty set of unique `clientWorkerKey` replicas at
  construction. Its repeated `extendEventDefinitions` calls append immutable
  business extensions, while zero extensions are valid; it provides no
  runtime registration, keyed lifecycle, or
  dynamic scale API. Each Manager owns one daemon Platform shared by only its
  replicas and never exposes managed JavaWorkers. One private group desired
  state is separate from each Worker's actual state; endpoint terminal never
  triggers restart until the Host explicitly invokes `reconcile()` or
  `start()`.
- `transport/android-worker` may depend on `transport/worker-core` and OkHttp,
  but not on `transport/java-worker`. Its network Client owns connection state,
  current-attempt identity filtering, callback serialization, stable-window
  accounting, and bounded fixed reconnect. A per-Client Handler on the
  Worker-owned Platform HandlerThread performs only connect and timer work;
  OkHttp protocol
  callbacks invoke the Transport and Handler synchronously on their callback
  thread. The Android Client does not delegate mutable reconnect state to Core.
  `AndroidWorker` owns
  application-scoped Identity, concrete Android Client assembly, and
  Application Context adaptation; Core owns Register/Bind preparation and the
  unified Worker Run Controller. Android Worker
  must not persist Endpoint URIs or cache or interpret Worker business
  messages. A Client endpoint terminal ends the current run; Android hosts
  decide when to call `start()` again.
  `AndroidWorker.create(...)` creates one package-private Platform containing
  OkHttp, one network HandlerThread, and one Control executor, but no Command
  executor, and performs no Identity or network I/O before `start()`. Its
  Definition parameter contains only Host business extensions; Core owns the
  final immutable registry. `start()` and `stop()` are non-blocking and may be called from the
  Main Looper; `close()` synchronously closes the Controller and then the
  Platform. Android hosts own process lifetime, permissions beyond INTERNET,
  static handler assembly, and backup policy.
- `server_jvm` may bind a `Map<adapterId, JsonNode>`, construct concrete
  Adapter instances, register them, and invoke `manager.start()` /
  `manager.close()` at process boundaries. Its `workerassembly` package may
  initialize explicitly configured WorkerGroup catalog metadata, bind one
  opaque Scenario Group JSON string plus a Lab root and Runtime API base URL,
  and sequence
  WorkerGroup initialization before Adapter and Scenario startup. It also owns
  the Worker Identity registry, persistent Endpoint Binding, and Adapter route
  verification, which are separate from Kernel Worker Runtime. It must not
  implement Handlers, parse
  Scenario Worker definitions, derive Worker IDs inside workerassembly, or
  derive Adapter URIs, or own concrete Worker construction, connection identity frame
  emission,
  connection waiting, generic class-name plugins, Redis bypass,
  Adapter scheduling, connection selection, queue
  handling, result buffering, or trusted rejection policy.
- `scenario_workers_jvm` may expose the final `ScenarioWorkers` aggregate
  lifecycle handle and its
  `fromJson(workerConfigJson, sandboxRoot, runtimeApiBaseUrl)`
  composition entry. Finite capability providers, parsed configuration, and
  Worker factories stay module-internal.
  Definitions and Handler instances are shared by all Workers that reference
  their event code and therefore must be stateless or thread-safe. The module may
  depend internally on Worker Core, the transport contract, and
  `transport:java-worker`; it must not depend on `kernel_jvm`, Spring,
  `server_jvm`, `transport:netty-adapter`, Redis, scores, Pacers, HTTP
  controller types, reflection, `ServiceLoader`, or configurable class names.
  The aggregate creates one `JavaWorkerManager` per non-empty configured
  WorkerGroup;
  every Manager owns one bounded daemon Platform shared only by its replicas.
  Scenario owns the local Lab and Property Index requests; closing Managers
  preserves every Worker JSON. Aggregate `start()` never waits
  for first Bind and does not expose Manager reconciliation. A configured
  Property Index update may wait for the Worker ID within the existing
  connection-timeout budget, then logs `14010` and skips when identity is
  still unavailable. The Lab scans only direct JSON children of configured
  Group directories, supports at most 100 Workers per Group, and has no file
  watcher or multi-process lock. Changing replica topology requires editing
  files and restarting the process.
- The default Server profile must not declare Adapter instances or Scenario
  WorkerGroups. Both are opt-in deployment assembly supplied by a profile,
  external configuration, or environment variables.
- The checked-in `scenario-workers` profile is local Lab capability and RPC
  assembly. It declares advisory WorkerGroup catalogs for the finite
  phone-number and string-utility Scenario capabilities plus the externally
  hosted Android demo capability. For every configured Group, Server creates or
  reuses one deterministic long-lived `ITEM_DRIVEN` Task and exposes it only
  through the WorkerGroup-scoped call route; Profile shutdown does not close
  those Tasks. Individual JVM Scenario Workers come only from the profile-owned
  `data/scenario-workers` Lab, never from an inline config array. A missing
  configured Group directory initializes only that Group's checked-in
  defaults; an existing directory receives no default mutation. WorkerGroup
  `eventCodes` are an advisory catalog summary and may lag the package-private
  Definition set resolved by Scenario Workers.

## JVM Incremental Assembly

`kernel_jvm` is intentionally one Gradle module. Packages may separate owner
responsibilities, but a new Gradle module requires a real publication,
dependency, or lifecycle boundary.

The module mirrors the public contracts exported by
`kernel_design.executable_spec.kernel`. A shared non-production manifest proves
interface, DTO, enum, and key-constant parity. Selected Redis providers
currently implement TaskItem append/result reads, Task/WorkerGroup descriptor
reads, WorkerGroup upsert, Worker upsert, Platform Properties patch, Worker/Platform
explicit indexed-property update/load, Worker score get/initialize plus an
unused parity reconcile mechanism, DeliveryCommand consume, and DeliveryReport
append. All other translated operations remain explicit gaps.

`server_jvm.kernelbinding` composes Task and Worker control/data providers:

```text
Task control operation -> Python HTTP provider
Worker resource/data operation -> Java Redis provider
Unmigrated operation -> explicit NOT_IMPLEMENTED
```

This provider matrix is incremental implementation evidence. It does not make
the Server a scheduling owner and does not weaken Python's role as the current
mechanism oracle.

Worker Delivery has a separate composition boundary:

```text
WorkerDeliveryOwnerAssemblyConfiguration
  -> WorkerCommandRuntime Redis provider
  -> WorkerResultRuntime Redis provider

WorkerDeliveryConfiguration
  -> shared codec
  -> Server point/batch HTTP application service

ServerWorkerAssemblyConfiguration
  -> WorkerGroup catalog JSON -> WorkerResourceCatalog
  -> deterministic scenario RPC Task per configured WorkerGroup
  -> WorkerGroup path -> internal Task catalog for Group RPC
  -> opaque Scenario Group JSON + fixed Lab root + Runtime API base URL
  -> ScenarioWorkers.fromJson

scenario_workers_jvm
  -> missing configured Group directory: materialize that Group's defaults
  -> configured WorkerGroup directory -> sorted direct Worker JSON files
  -> strict single-file Worker state and internal Definition resolution
  -> public Worker Register and Bind plus platform-issued WorkerId recovery
  -> Adapter-directed worker.connection.identify Report carrying
     WORKER + workerId source identity through the returned Adapter URI
  -> best-effort explicit Property Index updates
  -> package-private fixed capability definitions
  -> one JavaWorkerManager per WorkerGroup -> fixed JavaWorker replicas
  -> one internal Manager Platform -> group-local shared network resources

transport/netty-adapter
  -> one Adapter-private physical HTTP client shared only by three Remote APIs
  -> owner-local Command, Report, and route-verification Remote APIs
  -> first-seen-per-Adapter-process route verification through Server HTTP
  -> finite factory returning the public WorkerDeliveryAdapter contract
  -> one Adapter lifecycle/scheduler aggregate per configured instance
  -> one DeliveryCommandProcess with one private Command FiniteQueue
  -> one DeliveryReportProcess with one private Result FiniteQueue
  -> one shared identity/route/Result connection mechanism per instance
  -> process-local verified/pending/active/correlation Registry truth
  -> one complete WebSocket or Socket listener/EventLoop/Pipeline Server
  -> all-child physical Channel ownership and strict first-value identity
  -> direct fixed Adapter-local identity Report handshake
  -> unchanged encoded bound TASK Result forwarding and Adapter-owned error
     generation
  -> independent Netty WebSocket and Socket listeners
```

The main Server owns Worker Identity, persistent Endpoint Binding, Worker
Delivery HTTP, and their Redis boundaries. Identity and Binding use the
separate `wi:{prefix}:...` namespace and are not Kernel owners. It
composes and starts configured Adapter instances, then starts one configured
`ScenarioWorkers` aggregate handle. Every Adapter still reaches Worker
Delivery through the same batch HTTP boundary, and every Scenario Worker still
connects through an Adapter rather than an in-process fast path.

`worker_delivery_contract_jvm`, `transport/worker-core`, and
`transport/java-worker` compile to Java 11 bytecode. Android Worker consumes
the Core contract through a repository-local Gradle project dependency and
must not depend on Java Worker; none is a published SDK. They may not pull
Server, Kernel, Redis, scheduling, or Adapter implementations into the Worker
boundary.

## Verification

```text
python -m unittest discover -s kernel_design/executable_spec/tests
python -m compileall -q kernel_design/executable_spec
./gradlew :server_jvm:test
./gradlew :integrations:worker-capability-rpc:test
git diff --check
```

For real Redis proof, set
`KERNEL_DESIGN_REDIS_URL=redis://localhost:6379/15` before running the Python
suite.

The repository proof registry is [`TESTING.md`](TESTING.md). `Proof CI`
separates `:server_jvm:redisOwnerIntegrationTest`,
`:server_jvm:runtimeBoundaryIntegrationTest`, and the external
`:integrations:worker-capability-rpc:runRpcScenario` acceptance proof. A
Scenario or integration-only change must continue to select its owning lane
and reach the stable `Proof Gate`.
