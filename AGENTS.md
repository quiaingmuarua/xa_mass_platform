# XA Mass Kernel Agent Handoff

Status: current repository handoff.

## Mainline

- `kernel_design/` is the current mechanism oracle.
- `kernel_jvm/` is the JVM parity surface for Kernel owner contracts and
  selected owner-specific Redis providers. It does not implement scheduling,
  Pacers, score providers, or the Kernel application lifecycle.
- `server_jvm/` is the external Runtime API process. Controllers and services
  depend on `kernel_jvm` owner contracts. Its assembly binds control operations
  to Python HTTP providers and selected data/delivery operations to Java Redis
  providers; it does not define a second set of Kernel runtime ports.
- `worker_delivery_contract_jvm/` is the Java 21 transport-neutral
  WorkerCommand/DeliverSeed/SeedResult contract shared by Server and Worker.
- `worker_delivery_adapter_jvm/` owns the Adapter mechanism: a framework-free
  Gateway/active-connection/dispatch/result Core plus the concrete Spring
  WebSocket transport adaptation. It has no Spring Boot, Server, Kernel, or
  Redis dependency.
- `worker_jvm/` is the runnable one-slot Java reference Worker. Polling and
  WebSocket are transport profiles over one serial command execution core.
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
  Adapter Core may reach that facade only through the Adapter batch HTTP
  contract.
- `worker_delivery_adapter_jvm` must not depend on `server_jvm`, `kernel_jvm`,
  Spring Boot, Redis, scores, Pacers, or Server HTTP DTOs. Its Core packages
  must not depend on Spring WebSocket, create threads, or implement framework
  lifecycle. Only its `websocket` package may adapt Spring WebSocket
  connections and frames. Its private HTTP DTOs are proved against Server JSON
  with bilateral golden tests; do not add an in-process fast path.
- `worker_jvm` may depend only on the shared contract and Worker tool
  libraries. It must not depend on `server_jvm`, `kernel_jvm`, Python
  packages, Redis, score, Pacer, or TaskType.
- `server_jvm` may host the concrete WebSocket Adapter by configuring its
  endpoint and scheduling `dispatchOnce()`. Hosting must not move active
  connection selection, cursor handling, result buffering, or trusted
  rejection policy into Server.

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

worker_delivery_adapter_jvm
  -> Adapter batch HTTP client
  -> current connection registry, one-round dispatch, and result buffer
  -> concrete Spring WebSocket connection/frame adaptation
```

The main Server owns the HTTP and Redis boundaries. It can optionally host the
Adapter module's WebSocket endpoint and drive one scheduled Core round. Even
when embedded, the Adapter reaches Worker Delivery only through the same batch
HTTP boundary.

`worker_delivery_contract_jvm` is currently a repository-local Java 21 jar,
not a published SDK and not an Android compatibility promise. A future
Java/Android SDK may be evaluated only from a real external-consumer need; it
must not pull Server, Kernel, Redis, scheduling, or assembly implementations
into the Worker boundary.

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
