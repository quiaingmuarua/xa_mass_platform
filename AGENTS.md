# XA Mass Kernel Agent Handoff

Status: current repository handoff.

## Mainline

- `foundation_jvm/` contains only the cross-module `ErrorCode` and
  `CodedRuntimeException` mechanism. Owner modules define numeric ranges, use
  one coded exception per module, and retain HTTP, Worker outcome, retry, and
  logging policy.
- `kernel_design/` is the current mechanism oracle.
- `kernel_jvm/` is the JVM parity surface for Kernel owner contracts and
  selected owner-specific Redis providers. It does not implement scheduling,
  Pacers, score providers, or the Kernel application lifecycle.
- `server_jvm/` is the external Runtime API process. Controllers and services
  depend on `kernel_jvm` owner contracts. Its assembly binds control operations
  to Python HTTP providers and selected data/delivery operations to Java Redis
  providers; it does not define a second set of Kernel runtime ports.
- `worker_delivery_contract_jvm/` is the Java 11 compatible transport-neutral
  WorkerCommand/WorkerResult/WorkerConnectionBind contract shared by Server,
  Adapter, and Worker. Long-lived transports exchange direct command/result
  JSON after bind; there is no generic connection-message envelope.
- `transport/netty-adapter/` owns complete Adapter instances: local
  registration, start/close lifecycle, scheduled Gateway consumption, active
  connections, bounded Command/Result queues, and independent Netty
  WebSocket/Socket listeners. Worker result payloads remain opaque until
  Server ingress. It has no Spring, Server, Kernel, or Redis dependency.
- `transport/core/` is the Java 11 local core containing Worker execution,
  event definitions, error classification, and string-only network Client
  contracts. It contains no concrete network or platform implementation.
- `transport/okhttp-worker/` owns Worker Polling/WebSocket/Socket state
  machines plus the default OkHttp and JDK clients. It is not a CLI,
  application, Android wrapper, or business handler collection.
- `transport/android-client/` is an internal Android Library containing the
  HandlerThread/Looper OkHttp WebSocket client and the narrow
  `AndroidWebSocketWorker` production composition entry. Bind, command
  execution, and pending results remain owned by the reused
  `WebSocketWorkerTransport`; business handlers remain caller supplied.
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
- Keep `foundation_jvm` free of owner codes, HTTP status, log levels,
  framework dependencies, context maps, and generic utility classes.
- Keep coded exceptions to `errorCode + owner.method operation + message +
  cause`. Complete execution context belongs at log and tracing call sites.
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
  not read or mutate Task score, Worker score, candidate cache, Pacers, or
  ResultRouting consumption until a separate parity slice migrates that owner.
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
- `transport/core` may depend only on `foundation_jvm` and the shared Worker
  Delivery contract. It must compile with `--release 11` and must not import
  OkHttp, Android, Netty, Spring, Redis, Server, or Kernel implementations.
- `transport/okhttp-worker` may depend on `transport/core`, the shared Worker
  Delivery contract, OkHttp, and JDK networking. It must compile with
  `--release 11`, expose no OkHttp types, and must not import Android, JNDI,
  Server, Kernel, Redis, platform business handlers, score, Pacer, or
  TaskType.
- `transport/android-client` may depend on `transport/core`,
  `transport/okhttp-worker`, and OkHttp. Its network client serializes
  connection state and callbacks on a dedicated HandlerThread, filters stale
  connection callbacks, and must not cache or interpret Worker business
  messages. `AndroidWebSocketWorker` may only compose that client with the
  existing Worker transport and dispatcher. Android hosts own process
  lifecycle, permissions beyond INTERNET, and static handler assembly.
- `server_jvm` may bind a `Map<adapterId, JsonNode>`, construct concrete
  Adapter instances, register them, and invoke `manager.start()` /
  `manager.close()` at process boundaries. It must not host WebSocket
  endpoints, call `dispatchOnce()`, or own Adapter scheduling, connection
  selection, queue handling, result buffering, or trusted rejection policy.

## JVM Incremental Assembly

`kernel_jvm` is intentionally one Gradle module. Packages may separate owner
responsibilities, but a new Gradle module requires a real publication,
dependency, or lifecycle boundary.

The module mirrors the public contracts exported by
`kernel_design.executable_spec.kernel`. A shared non-production manifest proves
interface, DTO, enum, and key-constant parity. Selected Redis providers
currently implement TaskItem append/result reads, Task/WorkerGroup descriptor
reads, WorkerCommand consume, and WorkerResult append. All other translated
operations remain explicit gaps.

`server_jvm.kernelbinding` composes Task and Worker control/data providers:

```text
Kernel owner operation
  -> Python HTTP provider
  -> Java Redis provider
  -> explicit NOT_IMPLEMENTED
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

transport/netty-adapter
  -> Adapter batch HTTP client
  -> per-endpoint Command/Result loops and current connection registry
  -> direct WorkerCommand/WorkerResult transport, unchanged encoded Worker
     result forwarding, and Adapter-owned 3xxx generation
  -> independent Netty WebSocket and Socket listeners
```

The main Server owns the Worker Delivery HTTP and Redis boundaries. It only
composes and starts configured Adapter instances. Every Adapter still reaches
Worker Delivery through the same batch HTTP boundary.

`worker_delivery_contract_jvm`, `transport/core`, and
`transport/okhttp-worker` compile to Java 11 bytecode. The Android Client
consumes those repository-local libraries through Gradle project
dependencies; none is a published SDK. They may not pull Server, Kernel,
Redis, scheduling, or assembly implementations into the Worker boundary.

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
