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
- Keep `worker_delivery_contract_jvm` transport-neutral. HTTP and WebSocket
  packages may call `WorkerDeliveryService` but must not import owner Redis
  providers or owner runtime ports directly.
- `worker_jvm` may depend only on the shared contract and Worker tool
  libraries. It must not depend on `server_jvm`, `kernel_jvm`, Python
  packages, Redis, score, Pacer, or TaskType.
- One enabled Java WebSocket Adapter owns one configured non-system-polling
  endpoint-manager mailbox. Its session registry and bounded pump are
  process-local evidence, not Kernel Worker truth.

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

`server_jvm.kernelbinding` is the composition boundary:

```text
Kernel owner operation
  -> Python HTTP provider
  -> Java Redis provider
  -> explicit NOT_IMPLEMENTED
```

This provider matrix is incremental implementation evidence. It does not make
the Server a scheduling owner and does not weaken Python's role as the current
mechanism oracle.

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
