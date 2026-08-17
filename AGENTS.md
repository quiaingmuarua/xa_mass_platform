# XA Mass Agent Handoff

Status: current repository change contract.

Read the root [architecture entrypoint](README.md) for current system behavior
and [TESTING.md](TESTING.md) for proof ownership. This file governs how
agents change the repository; it is not the canonical mechanism narrative.

## Mainline

- `kernel_design/` is the mechanism oracle.
- `kernel_jvm/` is incremental contract/provider parity, not a second Kernel.
- `server_jvm/` is the Runtime API and application assembly, not a scheduler.
- `transport/` delivers already-decided Commands and executes endpoint-local
  handlers.
- Scenario, Android, integration and frontend modules are finite assembly,
  capability, acceptance or observation surfaces.

The stable authority rule is:

```text
Kernel    decides and converges scheduling
Server    exposes, validates, routes and correlates
Transport delivers and executes local events
```

Do not move candidate selection, Worker lease, TaskItem claim, retry, recovery
or Task finality into Server or Transport.

## Trust Order

1. `kernel_design/executable_spec/` code and focused tests.
2. Verified Redis behavior.
3. Current `kernel_design/doc/` owner contracts.
4. Current module README and root architecture entrypoint.
5. Historical tag material only as failure-mode evidence.

When code and a current document disagree, report and repair the drift. Do not
infer current behavior from `legacy-java-platform-final-2026-07-24`.

## Repository-Wide Rules

- Preserve explicit owners for truth, evidence, address, correlation,
  projection and hints.
- Treat every new Kernel operation as a long-lived cost commitment. Prefer
  caller-bounded identities, same-key aggregation and owner-local operations.
- Keep cross-key fan-out, global discovery, owner-spanning aggregation and
  background coordination in the caller or policy unless a named invariant
  proves otherwise.
- Keep scores opaque outside score-owner operations.
- Best-effort hints must not become correctness prerequisites.
- Do not add bridges, compatibility aliases, mirrored DTOs, fallback owners or
  speculative modules.
- Keep module-coded exceptions local: `errorCode + owner.method operation +
  message + cause`. Context belongs in safe logs and traces.
- JVM-only modules use `System.Logger`. Android-consumed Java 11 modules use
  `java.util.logging`.
- Never log opaque Worker payload or result content.
- Update the owning mechanism document in the same change as behavior.
- Use focused owner tests first and real Redis proof for Redis concurrency or
  atomicity claims.

## Kernel Design

`kernel_design/` owns clean mechanism contracts, Python executable
specification, owner Redis providers and Kernel application assembly.

- Scope Kernel searches, diffs and Python tests to `kernel_design/` by default.
- Task, TaskItem and Worker score truth remain independent.
- Score is a scheduling coordinate, not a resource write lock.
- Policy calls conservative owner operations; it does not widen owner APIs.
- Result Routing owns retry/finality disposition; Transport only carries
  evidence.
- Python HTTP adapters may expose only operations implemented by the current
  Python command host.
- Do not add Kotlin behavior without a named Python parity slice and proof.

Read [kernel_design/AGENTS.md](kernel_design/AGENTS.md) before changing this
workspace.

## Kernel JVM

`kernel_jvm/` remains one Java 21 Gradle module unless a real publication,
dependency or lifecycle boundary appears.

- Mirror only public contracts exported by the Python Kernel package.
- Missing operations fail with `KernelOperationNotImplementedException`.
- Java Redis operations live in the matching owner package.
- Server connection/health packages must not own Redis keys.
- Do not add Task score, candidate, Pacer or Result Routing behavior merely to
  improve parity percentage.
- Add a provider operation only with an explicit production caller and scoped
  parity proof.

## Server JVM

`server_jvm/` controllers and services depend on `kernel_jvm` owner contracts.
Provider selection belongs only to assembly.

Server may own:

- public API validation and error mapping;
- bounded use-case orchestration;
- Worker Identity and Endpoint Binding;
- Runtime projections;
- CONTROL_ONLY mailbox and request correlation;
- configured Adapter and Scenario startup.

Server must not own:

- candidate matching or Worker selection;
- scheduling lease, Item claim, retry, recovery or Task finality;
- Adapter queues, Channels or current route selection;
- Worker business handlers or Worker lifecycle;
- Redis bypass around an owner contract.

CONTROL_ONLY is an instance-local, best-effort Server use case. It observes the
pause score for admission, but does not create a Kernel mode or strong lock.
Its only public call route is scoped by `adapterId`; an optional same-Group
`workerId -> opaquePayload` map supplies Worker targets and per-target input,
not a WorkerGroup authority.
The unified Adapter consume endpoint may prefix a response with the Adapter
Control FIFO, then reads at most one Worker authority: CONTROL_ONLY when that
Worker Hash yields any command, otherwise TASK. Adapter-local Commands route by
`dst`; only Worker Command map keys carry workerId address meaning.
Control Call passes `messageType` and opaque payload through without an event
whitelist; future API Session authorization remains a separate owner.

The default profile declares no Adapter instances and no Scenario WorkerGroup.

## Worker Delivery Contract

`transport/worker-delivery-contract/` is Java 11 compatible and transport
neutral.

- `DeliveryCommand` target identity remains outside the DTO.
- `DeliveryReport` carries producer `src + sourceId`.
- `forward` remains opaque until its downstream owner.
- Do not add Server, Kernel, Redis, Netty, Android or scheduling dependencies.
- Long-lived connections use an Adapter-directed identity Report followed by
  direct Command/Report JSON; do not add a third connection envelope.

## Netty Adapter

The production cut is frozen:

```text
NettyWorkerDeliveryAdapter
  -> AdapterProcessManager
     -> DeliveryCommandProcess
     -> DeliveryReportProcess
  -> WorkerConnectionInboundHandler
     -> WorkerConnectionMechanism
        -> WorkerRouteRegistry
  -> NettyWorkerServer
     -> complete WebSocket or line-Socket implementation
```

Rules:

- The aggregate owns lifecycle and network shutdown ordering.
- `AdapterProcessManager` owns the finite Process list and one same-lifetime
  scheduler. It exposes no individual Process stop operation.
- Each Process owns one private thread-safe `FiniteQueue`; queues never cross
  owner boundaries.
- Owner-local Remote APIs own their paths, wire JSON and status semantics. One
  private HTTP client owns raw HTTP mechanics only.
- Connection mechanism owns identity interpretation, first verification,
  current route use and valid Result ingress. Registry owns route truth.
- The inbound Handler only adapts Netty callbacks.
- The physical Server owns listener, EventLoop, all child Channels, framing,
  physical writes and close behavior.
- Connection mechanism may retain `Channel` only as an address and must return
  physical operations to the Server.
- WebSocket and Socket share behavior tests, not a common lifecycle base.
- Adapter does not read score, select Workers or reinterpret Task policy.
- Adapter-local `platform.adapter.*` events use one immutable,
  composition-time Handler map;
  there is no runtime registration surface.
- `platform.adapter.events.snapshot` reports that process-local immutable map;
  it is observation evidence, not configuration or routing truth.
- Only valid bound Worker TASK/SYSTEM evidence follows the current destination
  rules; invalid unbound input and TASK result backpressure may close the exact
  connection.
- Shutdown waits are owner-local and bounded. Do not reset spent deadlines or
  add unbounded waits.
- Do not add Session, protocol SPI, dynamic Process/lane registry, reflection,
  ServiceLoader or an in-process Server shortcut.

`transport/netty-adapter` must not depend on Server, Kernel, Spring, Redis or
Pacer implementations. Server may depend only on its public finite factory and
the `WorkerDeliveryAdapter` contract.

## Worker Core And Platform Workers

`transport/worker-core` is Java 11 platform-neutral mechanism code.

- Core depends only on the delivery contract.
- Client owns networking and transparent reconnect.
- Transport owns identity/Command/Result protocol and synchronous event
  execution.
- `WorkerRunController` owns only the `RUNNING/STOPPED` run lifecycle.
- Core may use an injected Control Executor but creates and closes no thread,
  Executor or Scheduler.
- One Client callback lane serializes Commands. Do not add Command queues,
  in-flight registries or result caches.
- Event definitions are keyed by full Event Name and assembled before the
  Transport starts. Host code supplies short capability names through
  `WorkerEventDefinition.extension(...)`; Command `src` is evidence rather
  than a Handler lookup key.
- Java and Android assemblies prepend the finite default Worker management
  Definitions before Host extensions; Host code cannot replace their keys.
- `platform.worker.events.snapshot` reports the immutable assembled Event Names;
  it does not update WorkerGroup `eventCodes` or scheduling capability truth.
- Compatible optional payload additions may retain an Event Name. Incompatible
  input, output, semantics or side effects require a new name such as `.v2`;
  do not add alias, wildcard, prefix or fallback dispatch.
- Endpoint termination ends the current run; only an explicit Host `start()`
  begins another preparation.

`transport/java-worker` owns JVM networking/platform resources and exposes no
OkHttp types. `transport/android-worker` owns Android networking and
HandlerThread resources and must not depend on Java Worker. Neither may import
Server, Kernel, Redis, score, Pacer or platform business handlers.

## Scenario And Android Capabilities

`scenario_workers_jvm` is a finite Java 21 capability assembly, not a Kernel
owner, Server profile, Adapter, deployment unit or plugin system.

- It may depend on Worker Core and Java Worker, not Kernel, Server, Adapter,
  Redis, reflection or configurable class names.
- It owns local capability definitions, persistent Lab files and one
  `JavaWorkerManager` per configured non-empty WorkerGroup.
- Server owns profile coordinates and advisory WorkerGroup catalog setup.
- Existing Group directories are not seeded or repaired; missing configured
  directories may receive checked defaults.

Android capability modules own concrete immutable Definitions and Android data
access. They must not receive Worker identity, Endpoint, Task, Client,
Transport, Executor or Scheduler state. The demo Application is the assembly
and lifecycle owner; its Activity only observes and issues explicit local
controls.

## Integration And Frontend

- Integrations call public Runtime APIs and must not import Server, Kernel,
  Adapter or Worker implementations.
- Task Batch input/output files are real local Lab artifacts, not simulated
  results.
- Frontend is read only for Runtime truth and uses the public Task Batch API.
  It must not create scheduling, Worker identity or lifecycle truth.
- `frontend/public/overview.htm` is a human projection. Current truth remains
  in executable and owner documents.

## Verification

Use [TESTING.md](TESTING.md) as the only proof-lane registry. At minimum, run
the focused owner lane for touched behavior. Redis and runtime claims require
their named real-infrastructure proof.

Before completion:

- run `git diff --check`;
- scan for removed names, routes, imports and stale current docs;
- confirm archive material is not linked as current truth;
- report any skipped infrastructure proof explicitly.
