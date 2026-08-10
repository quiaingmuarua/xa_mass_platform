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
  and pass an opaque Worker manifest plus the public Runtime API base URL to
  the Scenario Worker module. It does not implement Scenario handlers or own
  individual Worker resource lifecycle.
- `scenario_workers_jvm/` is the Java 21 finite Scenario Worker assembly. It
  owns the checked-in phone-number and string-utility event definitions,
  strict Worker JSON parsing, real WebSocket Worker construction, public-HTTP
  identity registration, explicit best-effort Property Index updates, and
  aggregate resource cleanup. Its aggregate start does not wait for an initial
  WebSocket connection. It is not a Kernel owner, Server profile, Adapter,
  plugin SPI, or independently deployed application.
- `worker_delivery_contract_jvm/` is the Java 11 compatible transport-neutral
  WorkerConnectionBind/WorkerCommand/WorkerResult contract shared by Server,
  Adapter, and Worker. Long-lived transports send workerId-only Bind first and
  then exchange direct command/result JSON; there is no generic
  connection-message envelope.
- `transport/netty-adapter/` owns complete Adapter instances: local
  registration, start/close lifecycle, scheduled Gateway consumption, active
  connections, bounded Command/Result queues, and independent Netty
  WebSocket/Socket listeners. Worker result payloads remain opaque until
  Server ingress. It has no Spring, Server, Kernel, or Redis dependency.
- `transport/worker-core/` is the Java 11 local core containing Worker
  execution, event definitions, error classification, Worker
  Polling, `WorkerPreparation`, the two-state `WorkerRunController`, single-run
  text-message runtime, Host-injected Handler execution, and network
  Client contracts. It also owns the shared `WorkerLifecycle`, Identity store,
  Properties provider, and platform-neutral Register/Bind control contracts.
  The Client owns concrete networking and transparent reconnect, the Runtime
  owns Bind/Command/Result protocol, and `WorkerRunController` owns only the
  `RUNNING/STOPPED` run lifecycle. Core creates and closes no thread, Executor,
  or Scheduler and contains no concrete network or platform implementation.
- `transport/java-worker/` owns the `JavaWorker` WebSocket/line-Socket assembly,
  the fixed-WorkerGroup `JavaWorkerManager`, the process-scoped
  `JavaWorkerHostResources`, plus the default Java OkHttp
  point/WebSocket/control and JDK line-socket Client implementations. One
  Manager runs a configuration-fixed replica set for exactly one WorkerGroup;
  multiple Managers borrow one Host resource bundle and reconcile only when
  explicitly invoked. Neither is a second Worker state-machine owner, CLI,
  application, Android wrapper, automatic restart scheduler, or business
  handler collection.
- `transport/android-worker/` is an internal Android Library containing the
  HandlerThread/Looper OkHttp WebSocket Client, Android Register/Bind Client,
  long-lived Identity storage, and the complete `AndroidWorker` assembly. It
  implements Core's `WorkerLifecycle`, delegates its mechanism to the shared
  Worker Run Controller, and does not persist Endpoint URIs or implement a
  second command, result, session, or business-message cache.
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
- Use JDK `System.Logger` directly. Log numeric owner error codes, operation,
  and safe identifiers, never opaque Worker payload or result context.
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
  Adapter Runtime may reach that facade only through the Adapter batch HTTP
  contract.
- `transport/netty-adapter` must not depend on `server_jvm`, `kernel_jvm`,
  Spring, Redis, scores, Pacers, or Server HTTP DTOs. The Adapter module owns
  its complete instances, Netty listeners, scheduled dispatch loops, active
  connections, and bounded Command/Result queues. Worker results may be
  decoded only to enforce the `200/1xxx` Worker ingress boundary; their
  encoded JSON, payload, and forward context must not be rebuilt or interpreted.
  Only Adapter-owned `3xxx` construction is allowed. Its private HTTP DTOs are
  proved against Server JSON with bilateral golden tests; do not add an
  in-process fast path.
- `transport/worker-core` may depend only on the shared Worker Delivery
  contract. It must compile with `--release 11` and must not import OkHttp,
  Android, Netty, Spring, Redis, Server, or Kernel implementations.
  `RegisteredWorkerPreparation` owns Properties snapshotting, Worker ID
  recovery, and Register/Bind. `WorkerRunController` owns one two-state run,
  one synchronous Preparation call on the Host's calling thread, the current
  runtime, cooperative stop, and local lifecycle observation. Host code must
  inject Handler execution; Core must not create or shut down threads,
  Executors, or Schedulers. Lifecycle listeners are synchronous, lightweight level
  observations invoked outside the lifecycle state lock; `snapshot()` is
  authoritative and notifications may repeat. `WorkerRunController` does not
  accept or route Worker commands. The single-run runtime owns inbound WorkerCommand
  decoding and final admission, per-Worker serialized execution on the shared
  Handler pool, connection Bind, one-shot WorkerResult send, and exact-once
  terminal notification to `WorkerRunController`. A terminal Runtime drops a
  queued command; a Handler already running may finish but its result is
  discarded. It exposes no Bind, busy, reconnect,
  Handler, or exit-in-progress state to the lifecycle layer.
  `TextMessageClient` alone owns transient
  disconnect/failure handling and reconnect scheduling; its Runtime listener
  exposes only open, inbound message, and exact-once endpoint termination.
  It exposes no physical connection query or reconnect event; `send()` is the
  Runtime's only evidence that the current frame was accepted for sending.
  Preparation failure or endpoint termination ends the current Worker run;
  Core neither retries Preparation nor schedules another start. Only a later
  explicit Host `start()` may prepare another run. `WorkerLifecycle`, `JavaWorker`, and
  `AndroidWorker` expose only the two-state lifecycle snapshot and must not
  expose connection state, a local WorkerCommand injection method, or cached
  Worker business messages.
- `transport/java-worker` may depend on `transport/worker-core`, the shared
  Worker Delivery contract, OkHttp, and JDK networking. It must compile with
  `--release 11`, expose no OkHttp types, and must not import Android, JNDI,
  Server, Kernel, Redis, platform business handlers, score, Pacer, or TaskType.
  Standalone `JavaWorker.Builder.build()` requires Host-owned
  Handler `Executor`; `start()` performs Preparation synchronously and closing
  a JavaWorker must not shut the Executor down. `JavaWorkerManager.Builder`
  binds one WorkerGroup's shared
  capacity and a fixed, non-empty set of unique `clientWorkerKey` replicas at
  construction; it provides no runtime registration, keyed lifecycle, or
  dynamic scale API. Managers borrow process-owned resources and never expose
  managed JavaWorkers. One private group desired state is separate from each
  Worker's actual state; endpoint terminal never triggers restart until the
  Host explicitly invokes `reconcile()` or `start()`.
- `transport/android-worker` may depend on `transport/worker-core` and OkHttp,
  but not on `transport/java-worker`. Its network Client serializes connection
  state, generation filtering, callbacks, stable-window accounting, and
  bounded fixed reconnect scheduling on a
  dedicated HandlerThread. `AndroidWorker` owns application-scoped Identity,
  concrete Android Client assembly, and Application Context adaptation; Core
  owns Register/Bind preparation and the unified Worker Run Controller. Android Worker
  must not persist Endpoint URIs or cache or interpret Worker business
  messages. A Client endpoint terminal ends the current run; Android hosts
  decide when to call `start()` again.
  `AndroidWorker.Builder` requires a Host-owned Handler `Executor`; its
  synchronous `start()` must not run on the Main Looper, and closing an
  AndroidWorker must not shut the Executor down. Android hosts own process
  lifetime, asynchronous Control scheduling, Handler resource lifetime,
  permissions beyond INTERNET, static handler assembly, and backup policy.
- `server_jvm` may bind a `Map<adapterId, JsonNode>`, construct concrete
  Adapter instances, register them, and invoke `manager.start()` /
  `manager.close()` at process boundaries. Its `workerassembly` package may
  initialize explicitly configured WorkerGroup catalog metadata, bind one
  opaque Scenario Worker JSON string plus a Runtime API base URL, and sequence
  WorkerGroup initialization before Adapter and Scenario startup. It also owns
  the Worker Identity registry, persistent Endpoint Binding, and Adapter route
  verification, which are separate from Kernel Worker Runtime. It must not
  implement Handlers, parse
  Scenario Worker definitions, derive Worker IDs inside workerassembly, or
  derive Adapter URIs, or own concrete Worker construction, connection Bind frame
  emission,
  connection waiting, generic class-name plugins, Redis bypass,
  Adapter scheduling, connection selection, queue
  handling, result buffering, or trusted rejection policy.
- `scenario_workers_jvm` may expose the final `ScenarioWorkers` aggregate
  lifecycle handle and its
  `fromJson(workerConfigJson, runtimeApiBaseUrl)`
  composition entry. Finite capability providers, parsed configuration, and
  Worker factories stay module-internal.
  Definitions and Handler instances are shared by all Workers that reference
  their event code and therefore must be stateless or thread-safe. The module may
  depend internally on Worker Core, the transport contract, and
  `transport:java-worker`; it must not depend on `kernel_jvm`, Spring,
  `server_jvm`, `transport:netty-adapter`, Redis, scores, Pacers, HTTP
  controller types, reflection, `ServiceLoader`, or configurable class names.
  The aggregate creates one `JavaWorkerManager` per configured WorkerGroup and
  one bounded daemon `JavaWorkerHostResources` bundle shared by all Managers.
  Scenario retains Sandbox and Property Index ownership; it closes Managers,
  then sandboxes, then the shared resources. Aggregate `start()` never waits
  for first Bind and does not expose Manager reconciliation. A configured
  Property Index update may wait for the Worker ID within the existing
  connection-timeout budget, then logs `14010` and skips when identity is
  still unavailable. Changing replica count requires configuration change and
  process restart.
- The default Server profile must not declare Adapter instances or Scenario
  WorkerGroups. Both are opt-in deployment assembly supplied by a profile,
  external configuration, or environment variables.
- The checked-in `scenario-workers` profile is capability assembly only. It
  may declare WorkerGroups referencing the finite phone-number and string
  utility event codes, but it must
  not create Tasks or bind those Workers to RPC, Task type, or scheduling
  policy. WorkerGroup `eventCodes` are an advisory catalog summary and may lag
  the package-private Definition set resolved by Scenario Workers.

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
unused parity reconcile mechanism, WorkerCommand consume, and WorkerResult
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
  -> opaque Scenario Worker JSON + Runtime API base URL
  -> ScenarioWorkers.fromJson

scenario_workers_jvm
  -> strict Worker JSON parsing and internal Definition resolution
  -> public Worker Register and Bind plus platform-issued WorkerId recovery
  -> WorkerConnectionBind(workerId) frame through the returned Adapter URI
  -> best-effort explicit Property Index updates
  -> package-private fixed capability definitions
  -> one JavaWorkerManager per WorkerGroup -> fixed JavaWorker replicas
  -> one process JavaWorkerHostResources -> shared Worker Core execution

transport/netty-adapter
  -> Adapter batch HTTP client
  -> per-connection route verification through Server HTTP
  -> per-endpoint Command/Result loops and current connection registry
  -> direct WorkerCommand/WorkerResult transport, unchanged encoded Worker
     result forwarding, and Adapter-owned 3xxx generation
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
./gradlew build
git diff --check
```

For real Redis proof, set
`KERNEL_DESIGN_REDIS_URL=redis://localhost:6379/15` before running the Python
suite.

The JVM workflow additionally runs `:server_jvm:integrationTest` and the
external `:integrations:worker-capability-rpc:runRpcScenario` proof with
isolated Redis services and real Python Kernel / Java Server processes. A
Scenario or integration-only change must continue to trigger that workflow.
