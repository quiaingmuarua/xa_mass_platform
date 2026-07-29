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
  WorkerCommand/DeliverSeed/SeedResult contract shared by Server, Adapter, and
  Worker.
  It also owns the stable `messageType + payload` long-connection envelope;
  Adapter and Worker resolve payloads through separate local Definition
  Managers.
- `transport/netty-adapter/` owns complete Adapter instances: local
  registration, start/close lifecycle, scheduled Gateway consumption, active
  connections, bounded Command/Result queues, and independent Netty
  WebSocket/Socket listeners. Worker result payloads remain opaque until
  Server ingress. It has no Spring, Server, Kernel, or Redis dependency.
- `transport/okhttp-worker/` is the Java 11 compatible Worker library. It owns
  serial command execution, the handler contract, OkHttp Polling/WebSocket,
  and line-oriented Socket transport. It is not a CLI, application, Android
  wrapper, or business handler collection.
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
  connections, immutable message dispatcher, statically installed handlers,
  bounded Command/Result queues, and source-specific pending result batches.
  Handler assembly is not a dynamic registration or discovery surface.
  Worker `SeedResult` payloads must not be decoded or rebuilt in Adapter; only
  Adapter-owned `3xxx` construction is allowed. Its private HTTP DTOs are
  proved against Server JSON with bilateral golden tests; do not add an
  in-process fast path.
- `transport/okhttp-worker` may depend only on `foundation_jvm`, the shared
  Worker Delivery contract, and Android-compatible transport libraries. It
  must compile with `--release 11`,
  expose no OkHttp types, and must not import Android, JNDI, Server, Kernel,
  Redis, platform business handlers, score, Pacer, or TaskType. JVM and Android
  applications own threads, lifecycle, permissions, and static handler
  assembly.
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
reads, WorkerCommand consume, and SeedResult append. All other translated
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
  -> SeedResultRuntime Redis provider

WorkerDeliveryConfiguration
  -> shared codec
  -> Server point/batch HTTP application service

transport/netty-adapter
  -> Adapter batch HTTP client
  -> per-endpoint Command/Result loops and current connection registry
  -> stable long-connection envelope, immutable Definitions, opaque Worker
     result forwarding, and Adapter-owned 3xxx generation
  -> independent Netty WebSocket and Socket listeners
```

The main Server owns the Worker Delivery HTTP and Redis boundaries. It only
composes and starts configured Adapter instances. Every Adapter still reaches
Worker Delivery through the same batch HTTP boundary.

`worker_delivery_contract_jvm` and `transport/okhttp-worker` compile to Java 11
bytecode. They are repository-local libraries, not published SDKs. Neither may
pull Server, Kernel, Redis, scheduling, or assembly implementations into the
Worker boundary.

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
