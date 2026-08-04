# XA Mass Kernel Agent Handoff

Status: current repository handoff.

## Mainline

- `kernel_design/` is the current mechanism oracle.
- `kernel_jvm/` is the JVM parity surface for Kernel owner contracts and
  selected owner-specific Redis providers. It does not implement scheduling,
  Pacers, or the Kernel application lifecycle. Its Worker score provider is
  deliberately limited to get/initialize used by `WorkerRuntime.registerWorker`
  plus a parity-proved reconcile mechanism with no production caller; all
  scheduling score operations remain gaps.
- `server_jvm/` is the external Runtime API process. Controllers and services
  depend on `kernel_jvm` owner contracts. Its assembly binds Task control
  operations to Python HTTP providers and Worker resource, Task data, and
  delivery operations to Java Redis providers; it does not define a second set
  of Kernel runtime ports. It may
  pass an opaque JSON deployment manifest and a statically assembled
  event-code Definition map to the Scenario Worker module after starting
  configured Adapters. It does not implement Scenario handlers or own
  individual Worker resource lifecycle.
- `scenario_workers_jvm/` is the Java 21 finite Scenario Worker assembly. It
  owns the checked-in phone-number and string-utility event definitions,
  strict JSON deployment manifest parsing, generic WorkerGroup/Worker
  declaration lifecycle, real WebSocket Worker construction, initial
  connection wait, and aggregate failure cleanup. It invokes only existing Kernel owner contracts
  and is not a Kernel owner, Server profile, Adapter, plugin SPI, or
  independently deployed application.
- `worker_delivery_contract_jvm/` is the Java 11 compatible transport-neutral
  WorkerCommand/WorkerResult/WorkerConnectionBind contract shared by Server,
  Adapter, and Worker. Long-lived transports exchange direct command/result
  JSON after bind; there is no generic connection-message envelope.
- `transport/netty-adapter/` owns complete Adapter instances: local
  registration, start/close lifecycle, scheduled Gateway consumption, active
  connections, bounded Command/Result queues, and independent Netty
  WebSocket/Socket listeners. Worker result payloads remain opaque until
  Server ingress. It has no Spring, Server, Kernel, or Redis dependency.
- `transport/worker-core/` is the Java 11 local core containing Worker
  execution, event definitions, error classification, Worker
  Polling/WebSocket/Socket state machines, and string-only network Client
  contracts. It contains no concrete network or platform implementation.
- `transport/okhttp-worker/` owns only the default OkHttp point/WebSocket and
  JDK line-socket Client implementations. It is not a Worker state-machine
  owner, CLI, application, Android wrapper, or business handler collection.
- `transport/android-client/` is an internal Android Library containing only
  the HandlerThread/Looper OkHttp WebSocket Client. Android hosts explicitly
  compose that Client with Core's `WebSocketWorkerTransport` and caller-owned
  business definitions.
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
  Worker registration are get/initialize. Property update does not access
  score. Reconcile is parity-proved but has no
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
- `transport/okhttp-worker` may depend on `transport/worker-core`, the shared
  Worker Delivery contract, OkHttp, and JDK networking. It must compile with
  `--release 11`, expose no OkHttp types, and must not import Android, JNDI,
  Server, Kernel, Redis, platform business handlers, score, Pacer, or TaskType.
- `transport/android-client` may depend on `transport/worker-core` and OkHttp,
  but not on `transport/okhttp-worker`. Its network Client serializes
  connection state, generation filtering, callbacks, and fixed reconnect
  scheduling on a dedicated HandlerThread. It must not cache or interpret
  Worker business messages. Android hosts own Worker Transport construction,
  process lifecycle, permissions beyond INTERNET, and static handler assembly.
- `server_jvm` may bind a `Map<adapterId, JsonNode>`, construct concrete
  Adapter instances, register them, and invoke `manager.start()` /
  `manager.close()` at process boundaries. Its `workerassembly` package may
  bind one opaque Scenario Workers JSON string, statically flatten the finite
  capability Definition lists exported by `scenario_workers_jvm`, and sequence
  Adapter startup before the aggregate `ScenarioWorkers` handle. It must not
  implement Handlers, parse WorkerGroup configuration, derive Worker IDs or
  Adapter URIs, or own concrete Worker construction, owner registration/update
  behavior, connection waiting, generic class-name plugins, Server HTTP
  loopback, Redis bypass, Adapter scheduling, connection selection, queue
  handling, result buffering, or trusted rejection policy.
- `scenario_workers_jvm` may expose the final `ScenarioWorkers` aggregate
  lifecycle handle, its `fromJson` composition entry, and finite capability
  providers returning `WorkerEventDefinition` lists. Parsed configuration,
  generic WorkerGroup lifecycle, and Worker factories stay module-internal.
  Definitions and Handler instances are shared by all Workers that reference
  their event code and therefore must be stateless or thread-safe. The module may
  depend on `kernel_jvm` owner contracts and `transport:okhttp-worker`; it must
  not depend on Spring, `server_jvm`, `transport:netty-adapter`, Redis, scores,
  Pacers, HTTP controllers, reflection, `ServiceLoader`, or configurable class
  names.
- The default Server profile must not declare Adapter instances or Scenario
  WorkerGroups. Both are opt-in deployment assembly supplied by a profile,
  external configuration, or environment variables.
- The checked-in `scenario-workers` profile is capability assembly only. It
  may declare WorkerGroups referencing the finite phone-number and string
  utility event codes, but it must
  not create Tasks or bind those Workers to RPC, Task type, or scheduling
  policy. Each WorkerGroup's immutable `eventCodes` must exactly match every
  Definition set resolved for that group.

## JVM Incremental Assembly

`kernel_jvm` is intentionally one Gradle module. Packages may separate owner
responsibilities, but a new Gradle module requires a real publication,
dependency, or lifecycle boundary.

The module mirrors the public contracts exported by
`kernel_design.executable_spec.kernel`. A shared non-production manifest proves
interface, DTO, enum, and key-constant parity. Selected Redis providers
currently implement TaskItem append/result reads, Task/WorkerGroup descriptor
reads, WorkerGroup upsert, Worker registration/property update, Platform Properties patch, Worker/Platform
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
  -> opaque opt-in Scenario Workers JSON
  -> static eventCode-to-Definition map
  -> ScenarioWorkers.fromJson

scenario_workers_jvm
  -> strict WorkerGroup JSON parsing and Definition resolution
  -> WorkerResourceCatalog / WorkerRuntime owner registration and updates
  -> fixed capability definitions
  -> Worker Core + concrete network Client

transport/netty-adapter
  -> Adapter batch HTTP client
  -> per-endpoint Command/Result loops and current connection registry
  -> direct WorkerCommand/WorkerResult transport, unchanged encoded Worker
     result forwarding, and Adapter-owned 3xxx generation
  -> independent Netty WebSocket and Socket listeners
```

The main Server owns the Worker Delivery HTTP and Redis boundaries. It
composes and starts configured Adapter instances, then starts one configured
`ScenarioWorkers` aggregate handle. Every Adapter still reaches Worker
Delivery through the same batch HTTP boundary, and every Scenario Worker still
connects through an Adapter rather than an in-process fast path.

`worker_delivery_contract_jvm`, `transport/worker-core`, and
`transport/okhttp-worker` compile to Java 11 bytecode. The Android Client
consumes only the Core contract through a repository-local Gradle project
dependency; none is a published SDK. They may not pull Server, Kernel, Redis,
scheduling, or assembly implementations into the Worker boundary.

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
