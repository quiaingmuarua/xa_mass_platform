# XA Mass Agent Handoff

Status: current repository change contract.

Read the root [architecture entrypoint](README.md) for current system behavior
and [TESTING.md](TESTING.md) for proof ownership. This file governs how
agents change the repository; it is not the canonical mechanism narrative.

## Mainline

- `kernel_jvm/` owns stable Java mechanical contracts, Redis providers and
  score/resource mechanisms.
- `kernel_pacer_jvm/` is the fixed Java production policy and Pacer lifecycle
  over `kernel_jvm` owners.
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

1. Java production Owner and Pacer code.
2. Focused JVM tests and verified Redis behavior.
3. Current Owner documents.
4. Runtime Boundary and end-to-end proofs.
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
- Keep scores opaque outside score-owner operations. Opaque is a usage
  constraint, not a requirement to wrap every score in another class: a Pacer
  may retain, associate, exact-compare and return a raw score to its Owner, but
  must not decode, construct or calculate score coordinates.
- Build Redis keys from the fixed `xa_mass:<scope>` `RedisKeyspace` base;
  each owner appends only its own domain suffix. Proofs use unique `test_*`
  scopes and may clean only that exact scope with `SCAN` plus `UNLINK`; never
  use `KEYS`, `FLUSHDB`, or `FLUSHALL`.
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

## Java Kernel

[`doc/kernel/`](doc/kernel/) is the current Kernel documentation entry. Stable
mechanical Owners live in `kernel_jvm`; production policy and finite Pacer
lifecycle live in `kernel_pacer_jvm`.

- Task, TaskItem and Worker score truth remain independent.
- Score is a scheduling coordinate, not a resource write lock.
- Dispatch Policy may call a mechanical owner directly when the bounded
  decision and operation already belong to that owner. Add a finite internal
  Mechanism only when one legal transition composes owners or must protect an
  opaque exact fence; never add one merely to hide a direct call. Mechanisms
  must not absorb priority, matching, deficit, retry cadence or lane lifecycle
  policy. A set of Policy dependencies is not itself a Mechanism boundary.
- Result Routing Policy owns evidence parsing, bounded grouping and semantic
  event publication; Transport only carries evidence. Finite TaskItem, Worker
  execution and Worker Serviceability Mechanism ports decide which legal
  mechanical transitions implement each event. SUCCESS stores the TaskItem
  Result before separately requesting `FINAL_SUCCESS`; retryable FAILURE does
  not write an Item Result or decide Item finality. Result HASH is observed
  projection, while TaskItem Score is finality truth. The ordered Owner calls
  are not atomic, and no current replay or repair contract guarantees
  `Result.success => eventually FINAL_SUCCESS`.
- DeliveryReport and raw Worker lease scores stop at Result Policy boundaries.
  Task execution events may exact-release the correlated opaque Worker lease
  but must not infer connection polarity. Adapter Route/delivery-expiry
  evidence is consumed only by the optional Worker Serviceability policy and
  its named event Mechanism.
- All production Pacers run in `kernel_pacer_jvm` behind its finite
  `KernelPacerRuntime`; Server only adapts that lifecycle to Spring. Do not add
  a second Kernel host, Task business fallback, or alternate Pacer runtime.
- Result Convergence and Dispatch Convergence each have one fixed Java
  production application. Result Convergence owns the
  two Task lanes and optional Adapter Evidence lane. Its one coordinator owns
  shared bounded Batch capacity and fixed weighted-fair targets; Task SUCCESS
  and Task FAILURE may borrow idle capacity for concurrent owner-fenced Batches,
  while Adapter Evidence remains single-flight. Dispatch Convergence owns one
  Main Scheduler, one bounded Redis-time RUNNING Task observation and four
  fixed single-flight Resource Producers. The Main Scheduler plans the complete
  root input for Initialization, Allocation, Task Dispatch and optional
  Serviceability; a Producer may discover only vertical resources under those
  supplied Task or WorkerGroup identities. Dispatch policies may retain and
  return raw Score evidence to bounded Owner operations but must not decode or
  calculate Score coordinates. Claimed Commands may be constructed only by
  the package-private exact assignment closure after Worker renewal and Item
  claim.
- Do not add another language implementation without a named migration slice,
  one production owner and explicit proof of the cutover.

The current scheduling scale contract is deliberately vertical:

```text
small bounded active Task set
  -> each Task may contain many TaskItems
  -> each finite WorkerGroup may contain many Workers
```

The liveness target is work-conserving convergence, not per-Task fairness:

- fully occupied compatible Workers that keep completing assigned work are
  normal backpressure;
- bounded scan, exact CAS and Candidate refill may create short convergence
  delay;
- persistently due work plus persistently available compatible Workers that
  still cannot form any assignment across repeated eligible rounds is a
  scheduling liveness defect.

A full Task page alone does not classify the condition: check whether compatible
Worker capacity is actually idle. Do not add Task rotation, tenant fairness or
global Group discovery merely because some Tasks wait while available Workers
remain fully utilized. Massive active Task/WorkerGroup cardinality,
multi-tenant fairness, sharding and SaaS-scale isolation are separate future
architectures.

## Kernel JVM

`kernel_jvm/` is the more stable Java 21 mechanical-owner module.

- Keep public mechanical contracts caller-driven and bounded. Semantic Result
  event ports must have an explicit fixed Pacer caller and compose existing
  mechanical owners without adding Redis state or a second truth path.
- Missing operations fail with `KernelOperationNotImplementedException`.
- Java Redis operations live in the matching owner package.
- Server connection/health packages must not own Redis keys.
- Candidate Cache remains a stable mechanical owner here;
  Pacer policy and loop code do not.
- Task Owner enforces Task versus TaskItem rule location and JSON-safe finite
  persistence only. It must not interpret Match Property names or operators;
  stored rule semantics belong to the Pacer Matcher.
- TaskRuntime owns the Task-scoped self-describing Result HASH. Each value
  contains `code + non-empty opaqueResultPayload`; exact `200` is success.
  Success replaces an earlier failed value, while terminal failed storage uses
  one fixed internal failure description with `HSETNX` and cannot replace any
  observed Result. Missing remains not observed; there is no companion
  classification key. Result reads must not be used to infer TaskItem finality,
  and Score reads must not be used to infer a Result code or payload.
- Finite Result semantic event ports live with the Task/Worker owners; their
  default implementations may compose bounded mechanical operations but must
  not accept DeliveryReport, lane identity, JSON or Adapter Event Names.
- Do not add Task score or owner behavior merely to broaden an API surface.
- Add a provider operation only with an explicit production caller and scoped
  owner proof.

## Kernel Pacer JVM

`kernel_pacer_jvm/` is the Kernel-owned, faster-moving policy and lifecycle
module. Its dependency direction is `server_jvm -> kernel_pacer_jvm ->
kernel_jvm`.

- `KernelPacerRuntime` is its only externally supported production entry.
  The `result` and `dispatch` packages may each expose exactly one narrow
  module-internal lifecycle bridge required by Java package visibility; no
  module outside `kernel_pacer_jvm` may import either bridge.
- It owns fixed Assignment, Result Routing and Worker Serviceability policy,
  configuration interpretation, Pacer loops and their finite lifecycle.
- Task Dispatch must store the failed Result marker before promoting an
  exhausted or TTL-expired Item to `FINAL_FAILED`; a failed write leaves the
  score unchanged for a later round. Result FAILURE only releases the
  correlated Worker lease. When late SUCCESS evidence is consumed it may
  replace failed and request promotion of the existing score to
  `FINAL_SUCCESS`; destructive consumption and the separate Owner calls do not
  provide unconditional eventual convergence.
- `WorkerCandidateSelectionPolicy` owns the three fixed source operations:
  shared HOT acquisition for Task-rule precomputation, cached candidate
  renewal, and Item-rule on-demand acquisition. It also owns Score eligibility,
  priority/count/unique selection and exact lease. There is no generic
  acquisition Strategy or cached-to-on-demand fallback. The
  package-private `WorkerCandidateMatcher` owns one call-local Match Plan,
  rule-derived Worker identity ranges, canonical Rule Match and original-pair
  post-lease rematch. Selection must not interpret Property names or Constraint
  operators; Matcher must not read Score, Candidate Cache, workflow labels or
  selection policy. `WorkerAllocationMechanism` is a fixed Producer workflow
  label, not a Matcher mode; Task and TaskItem rule workflows are mutually
  exclusive and do not exchange Candidate Cache entries.
- It does not own Redis keys, mechanical owner state, Spring assembly, HTTP or
  deployment.
- Do not add a Pacer SPI, dynamic registry, further public internal Pacer type,
  reflection, ServiceLoader or a second external runtime entry.
- Prove policy behavior through focused Pacer tests and Redis-sensitive
  behavior through the stable owner ports.

## Server JVM

`server_jvm/` controllers and services depend on `kernel_jvm` owner contracts;
Spring assembly additionally depends only on the public
`kernel_pacer_jvm` runtime entry. Provider selection belongs only to assembly.

Server may own:

- public API validation and error mapping;
- bounded use-case orchestration;
- Worker Identity and Endpoint Binding;
- create-only WorkerGroup registration and bounded Runtime projections;
- Runtime projections;
- DIRECT_CALL admission and request correlation;
- bounded Worker Serviceability request/result routing without score policy;
- configured Adapter and Scenario startup.

Within the versioned HTTP Contract, use a direct JSON scalar, collection or
Map when that is the complete body. Add a named DTO only for a combined
contract, an independently meaningful structured resource, a status result
item or a cross-field invariant. Do not add generic envelopes, `SimpleRequest`
or one-field status wrappers.

`ActionOutcome` is the one shared mutation-effect item for APIs that genuinely
share `applied | unchanged | rejected` semantics. Single-resource actions use
only `applied` or `unchanged`; `rejected` carries the business code and message
for an independently failed member of a batch. Whole-request failure still
uses `ApiErrorResponse`. Do not turn `ActionOutcome` into a generic response
envelope or force identity, observation, delivery or protocol results into it.

Task `items:call` and `results:load` expose the shared
`succeeded | failed | not_observed` Result view; only succeeded carries an
opaque payload. This is a Result projection and must not be treated as
TaskItem Score finality. Export remains finite-Task, terminal-only and
success-only: it scans the unified owner pages and filters failed without
reading Redis directly or adding failed/all modes.

Server must not own:

- candidate matching or Worker selection;
- scheduling lease, Item claim, retry, recovery or Task finality;
- Adapter queues, Channels or current route selection;
- Worker business handlers or Worker lifecycle;
- Redis bypass around an owner contract.

DIRECT_CALL is a caller-targeted, best-effort Server use case. It does not
observe or change Worker score and creates no Kernel mode or strong lock.
Its only public call route is scoped by `adapterId`; an optional same-Group
`workerId -> opaquePayload` map supplies Worker targets and per-target input,
not a WorkerGroup authority.
The unified Adapter consume endpoint may prefix a response with the Adapter
Direct FIFO, then consumes once from the shared Worker Command Hash. Only
remaining response capacity may carry one Kernel Serviceability Adapter
snapshot Command. Worker Direct Calls use the owner `offer` operation and
cannot replace an occupied slot; authoritative TASK append may replace an
unconsumed Direct Command.
Adapter-local Commands route by `dst`; only Worker Command map keys carry
workerId address meaning. Direct Call passes `messageType` and opaque payload
through without an event whitelist; future API Session authorization remains a
separate owner.

The default profile declares no Adapter instances and no Scenario WorkerGroup.
The built-in `agentforge` Profile is a finite downstream preset with one
WebSocket Adapter and no configured WorkerGroup or capability implementation.

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
- Result queue capacity is only a local soft memory bound. The Report Process
  submits fixed `1..100` remote batches; retries retain that exact batch and
  shutdown must not unboundedly drain the queue.
- Connection mechanism owns identity interpretation, first verification,
  current route use and valid Result ingress. Registry owns route truth.
- Registry keeps one atomic pending, connected or disconnected Route entry per
  workerId; do not split route and verification facts across parallel Maps.
- Only disconnected verification evidence may be TTL/capacity cached. Active
  and pending routes cannot be evicted by cache policy. A Channel attribute
  contains only the claimed workerId for callback correlation and never mirrors
  verification truth. The independent
  properties projection is capacity bounded, not time deleted. Its visibility
  follows retained route verification evidence, coordinated only by the
  connection mechanism.
- Caffeine is connection-owner storage infrastructure only. Do not leak it to
  Server, Process, Remote API or physical Network owners, and do not install a
  loader, refresh, listener, scheduler or removal side effect.
- Registry routes only by `workerId`; long-lived identity and Kernel-requested
  Adapter snapshots do not add WorkerGroup state to the route owner.
- Adapter-local Worker property observation is a separate projection cache;
  it must not be folded into RouteEntry or copied into Server/Kernel truth.
- Only an explicit successful Worker properties snapshot Result from the exact
  current Channel may refresh that projection; connection activation does not
  request a snapshot and cache refresh never emits a Kernel Report.
- Connection snapshots read Route truth; properties snapshots pass through the
  Route evidence gate and then read the properties cache. They have no atomic
  join or shared version.
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
- Expired TASK delivery atomically offers its 23002 TASK Report and a separate
  `platform.adapter.worker-delivery.expired` KERNEL Report to the one Report
  Process; Transport does not interpret either as score policy.
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
- Each ordinary explicit `start()` loads the complete Worker Properties and
  refreshes canonical truth through one Prepare call. A Java Manager may
  optionally batch up to 100 stopped replicas and inject the returned
  `PreparedWorker` coordinates into those runs; Core owns neither that HTTP
  batch nor Host Properties aggregation. Ordinary Java and Android identity
  uses the default `CLIENT_KEY` Worker kind and `clientWorkerKey`; Android
  sends that kind explicitly. A tagged batch may select another bounded
  Server-owned identity policy; Core and Manager do not interpret it. Workers
  do not persist or hint workerId. Worker kind only selects the Server-owned
  typed registration-key algorithm; it never enters the Redis key address.
  One WorkerGroup has one identity Hash, and the typed registration-key output
  prevents one algorithm from aliasing another. Transparent Client
  reconnect sends only connection identity; there is no runtime
  Properties-change event.
- Core may use an injected Control Executor but creates and closes no thread,
  Executor or Scheduler.
- Active `stop()` revokes the current run before closing its Client outside
  the run-state gate. The Java WebSocket Client does not wait for a Handler or
  Transport callback. Stop during Prepare only discards that single-flight
  Prepare result; it is not a paused Worker.
- Worker owns no pause or delivery-admission state. Any future pause remains
  Kernel scheduling truth and Adapter delivery or Route behavior.
- One physical Client attempt preserves protocol callback order. Core adds no
  cross-Attempt or cross-run Handler fence; a callback admitted before close
  may finish after the run ends. Do not add Command queues, in-flight
  registries or result caches.
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

`transport/java-worker` is Java 21 and owns JVM networking/platform resources.
Its Manager Platform supplies virtual-thread executors for active OkHttp
WebSocket readers and OkHttp's internal WebSocket TaskRunner, and sets
Dispatcher capacity from the fixed replica count. The latter prevents a
reconnect burst from expanding OkHttp's default unbounded platform-thread
backend. Control and reconnect scheduling remain bounded ordinary threads. Do
not move virtual threads, Dispatcher, OkHttp or connection capacity into Core.
It exposes no OkHttp types. `transport/android-worker` owns Android networking and
HandlerThread resources and must not depend on Java Worker. Neither may import
Server, Kernel, Redis, score, Pacer or platform business handlers.

## Scenario And Android Capabilities

`scenario_workers_jvm` is a finite standalone Java 21 Lab Worker Host, not a
Kernel owner, Server profile, Adapter, production Worker platform or plugin
system.

- It may depend on Worker Core and Java Worker, not Kernel, Server, Adapter,
  Redis, reflection or configurable class names.
- It owns local capability definitions, persistent Lab files and one
  `JavaWorkerManager` per configured non-empty WorkerGroup.
- Each direct `.jsonl` child is a strict line inventory: one schema-v2 Worker
  record per physical line, at most 100 lines per file and at most 10,000
  records per configured Group. Every record carries
  immutable `labInventoryKey` and `labInventoryLine` Properties matching its
  physical location. `<filename>:<line>` is only the Lab-local `labWorkerKey`,
  not a universal Worker identity field. Scenario uses `SCENARIO_LAB` batch
  Prepare for initial file batches and one-record Lab HTTP starts; ordinary
  Prepare remains available to other Java and Android Workers.
- Batch Prepare holds no Manager lifecycle lock or cross-replica gate.
  Concurrent requests may repeat the Server-owned idempotent Prepare for one
  stopped replica, while its Controller still installs at most one run.
  Scenario resolves a control target under its short inventory gate, then
  performs Manager Prepare outside that gate so a slow control request cannot
  block scheduled stops or Host shutdown from entering their own owners.
- An optional strict startup plan selects the initial finite Worker set and
  startup-only scheduled stops. It is validated completely before any replica
  starts and does not contain Properties, Worker IDs, Tasks or Kernel claims.
- The String Lab command checkpoint is a bounded Scenario-only fault fixture.
  It must not become a Worker Core hook, generic action DSL or production
  lifecycle mechanism.
- Its loopback Lab HTTP surface may atomically replace discovered Worker files,
  explicitly start/stop one replica, and own nonpersistent scheduled stops.
  Lab desired/runtime state is local observation, never Adapter or Kernel truth.
- Atomic Worker file replacement fails closed when the filesystem cannot honor
  `ATOMIC_MOVE`. A start issued while the Manager's previous stop is still
  converging is a conflict; callers observe `STOPPED` before retrying.
- Every explicit Worker start reopens its complete Properties file. There is no
  watcher, automatic reconcile, dynamic inventory, or generic fault DSL.
- Server owns profile coordinates and create-only advisory WorkerGroup seeds.
- Server never depends on, constructs, starts or stops the Host. The root local
  launcher and proof lanes own the two independent process lifecycles.
- Existing Group directories are not seeded or repaired; missing configured
  directories may receive checked defaults.
- State/server and in-flight-loss convergence are independent Integration
  scenarios. Their runner owns process failure and phase progression; the Host must
  not infer Adapter connectivity or Kernel serviceability from local state.
- The Lab is a mutation source and local witness, not a reconcile robot or
  distributed consistency Owner. Each Harness action is issued once; the
  Harness records whether its local effect was established and only then compares
  independent Runtime projections. They must not retry, compensate, restore or
  reshape the Lab merely to make a convergence assertion pass.

Android capability modules own concrete immutable Definitions and Android data
access. They must not receive Worker identity, Endpoint, Task, Client,
Transport, Executor or Scheduler state. The demo Application is the assembly
and lifecycle owner; its Activity only observes and issues explicit local
controls. Demo-only `extension.worker.lab.delay` and `.fail` Definitions are
finite Android proof fixtures, not Android Worker SDK APIs. Their local active
Handler count establishes only a Lab mutation and must not be promoted to
Adapter connectivity, Kernel state or schedulability.

## Integration And Frontend

- Integrations call public Runtime APIs and must not import Server, Kernel,
  Adapter or Worker implementations.
- `:integrations:android-worker-proof` is the Java 21 assertion Owner for the
  API 33 Android Worker lane. The Debug App owns single-Worker lifecycle claims;
  it also proves one Task recovery after the App process dies inside a DELAY
  Handler. Three fixed Lab application IDs add same-Group process isolation and
  partial outage claims without repeating endpoint or Server restart proofs. Its
  shell owns only Emulator, ADB, Server, App and Redis-scope processes, and
  disables cached-app freezing on that disposable Emulator. This is not
  background-survival evidence. The Java
  Harness uses device-local state to establish mutations, then independently
  observes public Network, Scheduling, Direct Call and finite Task APIs. It
  retries only temporary HTTP observation failures; invalid contracts and
  identity drift fail immediately. It never asserts business Result payloads
  and is not a Java Worker witness. Dynamic Properties re-Prepare, Doze/OEM
  policy, device matrices, throughput and arbitrary App counts are separate
  claims.
- One-shot Python proof runners may own process orchestration, but not database
  protocols. Redis scope cleanup uses
  `.github/scripts/cleanup_redis_test_scope.py` and `redis-py`; do not add a
  lane-local RESP client. Keep one runner entrypoint per high-level proof lane
  rather than hiding distinct failure sequences in a generic scenario runner.
- Worker Convergence Health may combine the loopback Lab API with independent
  Runtime Preview, Network, Scheduling, and finite Task APIs. Its evidence may
  record identities and projected states but never business payload. Failed or
  ambiguous Lab operations are non-evidence, not Adapter or Kernel failures;
  each deterministic scenario stops mutation injection and evaluates the
  actual observed local world instead of installing a preferred final world.
  It uses managed ON_DEMAND batch calls as offered load and `results:load` only
  for named witnesses. It must not turn `NOT_OBSERVED` into failure, require all
  offered Items to succeed, count `FAILED` as a successful witness, poll
  `results:export`, or repeat PRECOMPUTED and topology claims owned by Runtime
  Boundary.
- Worker WebSocket Scale is a separate nightly/manual Linux offered-load lane.
  It may generate one 10,000-record Scenario Group, observe existing Runtime
  APIs in 100-ID pages, restart Server once while retaining the Host, and record
  `/proc` resource evidence. Its fixed claim is 10,000 prepared identities and
  at least 9,900 connected-and-HOT Workers, not exact online convergence,
  Handler concurrency, throughput, latency, or soak behavior.
- Worker Correctness inputs are caller-owned local files. Its perfect-world
  proof uses managed batch `items:call`, fixes exact Item statuses and treats
  Result payload as opaque. The frontend separately turns lines into ordinary
  finite TaskItems through public Task APIs; Server owns no Lab input/output
  directory.
- Frontend is read only for Runtime truth. Its finite Task file flow may create,
  append, approve, and export only through public Task APIs and must not infer
  scheduling state from elapsed time.
  Its single-Worker Direct Debug action may invoke only the public
  Adapter-scoped DIRECT_CALL API. That action remains caller-targeted and
  best-effort; its response must not be promoted to schedulability, capability,
  Worker identity or lifecycle truth.
  It must not create scheduling, Worker identity or lifecycle truth.
- `frontend/public/overview.htm` is a human projection. Current truth remains
  in executable and owner documents.
- `distribution/server` is a packaging owner only. It assembles the current
  Server, production Pacer, frontend and configuration. It must not package the
  repository-local Scenario Worker Host, add a fallback runtime owner, add a
  second production mechanism or introduce scheduling behavior.

## Verification

Use [TESTING.md](TESTING.md) as the only proof-lane registry. At minimum, run
the focused owner lane for touched behavior. Redis and runtime claims require
their named real-infrastructure proof.

Before completion:

- run `git diff --check`;
- scan for removed names, routes, imports and stale current docs;
- confirm archive material is not linked as current truth;
- report any skipped infrastructure proof explicitly.
