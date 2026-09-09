# XA Mass Agent Handoff

Status: current repository change contract.

Read the root [architecture entrypoint](README.md) for current system behavior
and [TESTING.md](TESTING.md) for proof ownership. This file governs how
agents change the repository; it is not the canonical mechanism narrative.

## Applying This Contract

- Start with the current Git HEAD and worktree. Preserve unrelated edits and
  read the affected Owner, callers and proof before changing their behavior.
- Follow the user's requested outcome and scope. An authorized implementation
  plan covers its necessary slices; a slice boundary, status label or missing
  template field does not require another approval. Review-only requests remain
  read-only. Ask only when a material unresolved choice affects the next action;
  continue independent authorized work.
- The ownership and composition rules below govern changes within the current
  architecture. An explicitly requested migration may revise the affected
  contract together with its callers, Owner document and proof. Words such as
  "fixed" or "frozen" do not prohibit that authorized migration, and permission
  to harden an implementation does not authorize unrelated boundary changes.
- Owner links lead to the complete applicable contract. Keep mechanism flow,
  storage shape, configuration values and scenario thresholds there; retain
  change constraints here. Read the linked detail when changing that mechanism.
- Skills and historical memory provide working guidance, not current project
  truth or permission to expand the task. Retrieve historical versions only
  when the requested investigation needs them; do not restore retired designs
  or historical defects as default constraints.

## Mainline

- `kernel_jvm/` owns stable Java mechanical contracts, Redis providers and
  score/resource mechanisms.
- `kernel_pacer_jvm/` is the fixed Java production policy and Pacer lifecycle
  over `kernel_jvm` owners.
- `worker_matching_jvm/` owns Worker/Platform Properties, PRECOMPUTED
  Candidate Rules, constraint interpretation and ordered candidate
  publication.
- `server_jvm/` is the Runtime API and application assembly, not a scheduler.
- `transport/` delivers already-decided Commands and executes endpoint-local
  handlers.
- Scenario, Android, integration and frontend modules are finite assembly,
  capability, acceptance or observation surfaces.

The stable authority rule is:

```text
Kernel    decides and converges scheduling
Matching  interprets Worker facts and rules into bounded identity evidence
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
5. Historical material, when relevant, only as version-scoped evidence.

Use code and tests to establish current behavior, and the requested outcome
and applicable contracts to establish intended behavior. A planned migration
will differ from its starting implementation. For documentation drift, correct
the description within scope; do not change production behavior just to make
it match prose. Report a confirmed code defect with its evidence and repair it
only when the task covers that behavior.

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
- A bounded evidence handoff may remain lossy across process failure, but local
  queue capacity must be surfaced as retryable backpressure when the upstream
  already owns a bounded retry path; do not misclassify it as semantic input
  rejection.
- Do not add bridge layers, compatibility aliases, mirrored DTOs, fallback
  owners or speculative modules. The bounded Pacer lifecycle bridges below
  are the explicit internal assembly exception.
- Keep module-coded exceptions local: `errorCode + owner.method operation +
  message + cause`. Context belongs in safe logs and traces.
- JVM-only modules use `System.Logger`. Android-consumed Java 11 modules use
  `java.util.logging`.
- Never log opaque Worker payload or result content.
- Update the owning mechanism document in the same change as behavior.
- Use focused owner tests first and real Redis proof for Redis concurrency or
  atomicity claims.

## Java Kernel

[Current Kernel documents](doc/kernel/README.md) own mechanism narratives.

- Task, TaskItem and Worker score truth remain independent.
- TaskItem tag 1 is the only ACTIVE scheduling band; tags 2..9 are generic
  TERMINAL outcomes. Only strictly increasing scores may replace existing
  members; terminal outcomes never reopen Item or Task scheduling. Server owns
  business names and supplies execution outcome tags through Pacer assembly.
  ACTIVE claims retain exact-score CAS. Result content remains independent of
  these generic outcome transitions.
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
  Result before separately requesting `TERMINAL(tag=6)`; retryable FAILURE does
  not write an Item Result or decide Item finality. Result HASH is observed
  projection, while TaskItem Score is finality truth. The ordered Owner calls
  are not atomic, and no current replay or repair contract guarantees
  `Result.success => eventually TERMINAL(tag=6)`.
  OUTCOME_OBSERVATION advances the existing Item Score before optionally storing
  content and never touches Worker leases or reopens Task scheduling. State and
  content retain independent maximum targets; Score NOOP can still admit newer
  millisecond content within one slot. No ACK, replay or repair is implied.
- DeliveryReport, JSON and lane identity stop at Result Policy boundaries.
  Worker execution events receive correlated opaque `WorkerLeaseReference`
  values rather than raw lease scores and must not infer connection polarity.
  Adapter Route/delivery-expiry and internal Server Polling
  evidence is consumed by Worker Serviceability policy in every preset and
  its named event Mechanism.
- All production Pacers run in `kernel_pacer_jvm` behind its finite
  `KernelPacerRuntime`; Server only adapts that lifecycle to Spring. Do not add
  a second Kernel host, Task business fallback, or alternate Pacer runtime.
- Result and Dispatch Convergence each have one fixed production application.
  Preserve their separate capacity and lifecycle ownership and the concurrency
  limits in [Pacer assembly](kernel_pacer_jvm/doc/application-assembly.md).
  Dispatch has one bounded Redis-time Task observation. Its Main Scheduler
  supplies complete root input to single-flight Producers; discovery beneath
  that input must stay vertical. Claimed Commands may be constructed only by
  the package-private exact assignment closure after Worker confirmation and Item
  claim. Result lane scheduling belongs to
  [Result Policy](kernel_pacer_jvm/doc/result/result-routing-scheduling.md).
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

[Mechanical Owners](kernel_jvm/README.md) own contracts, Score transitions and Redis shapes.

`kernel_jvm/` is the more stable Java 21 mechanical-owner module.

- Keep public mechanical contracts caller-driven and bounded. Semantic Result
  event ports must have an explicit fixed Pacer caller and compose existing
  mechanical owners without adding Redis state or a second truth path.
- Missing operations fail with `KernelOperationNotImplementedException`.
- Java Redis operations live in their owning module and package.
- Server connection/health packages must not own Redis keys.
- Candidate Cache remains a stable mechanical owner here;
  Pacer policy and loop code do not.
- Task Owner stores only scheduling descriptors and TaskItem execution data.
  It must not store or interpret PRECOMPUTED allocation Rules, Match Property
  names or constraint operators; persistent Candidate Rules and their
  semantics belong to Worker Matching. It does own the closed ON_DEMAND
  `workerSelector` instruction and persists only normalized Worker IDs.
- TaskRuntime owns the self-describing Result projection and its
  [storage contract](kernel_jvm/doc/runtime-redis/task-result-runtime-redis-shape.md).
  A success may replace an earlier failed Result; storing terminal failure
  cannot replace any observed Result. An absent Result remains not observed.
  Successful content shares its HASH field with tag and reported milliseconds:
  higher tag wins, then strictly later milliseconds. Equal targets retain content,
  and state-only observations never erase it. Corrupt records are not overwritten.
  Do not add a companion classification key. Result reads must not infer
  TaskItem finality; Score reads must not infer Result code or payload.
- Finite Result semantic event ports live with the Task/Worker owners; their
  default implementations may compose bounded mechanical operations but must
  not accept DeliveryReport, lane identity, JSON or Adapter Event Names.
- Do not add Task score or owner behavior merely to broaden an API surface.
- Add a provider operation only with an explicit production caller and scoped
  owner proof.

## Kernel Pacer JVM

[Pacer assembly](kernel_pacer_jvm/doc/application-assembly.md) and its linked Policy documents own current workflows.

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
  exhausted or TTL-expired Item to `TERMINAL(tag=5)`; a failed write leaves the
  score unchanged for a later round. Result FAILURE only releases the
  correlated Worker lease. When late SUCCESS evidence is consumed it may
  replace failed and request promotion of the existing score to
  `TERMINAL(tag=6)`; destructive consumption and the separate Owner calls do not
  provide unconditional eventual convergence.
- [Allocation Policy](kernel_pacer_jvm/doc/dispatch/task-worker-allocation-pacer.md)
  owns PRECOMPUTED deficits, priority, initial holds and ordered Match Demand.
  [Candidate Selection](kernel_pacer_jvm/doc/dispatch/assignment-dispatch-scheduling.md#candidate-selection)
  belongs to Task Dispatch and owns acquisition and endpoint-bearing assembly.
  Neither Pacer Policy may read Rules or Properties or interpret constraints;
  [Matching](worker_matching_jvm/README.md) owns that interpretation and may
  append accepted held candidates through the Kernel Cache Owner. Dispatch
  exact-confirms the clean cached score, consumes its eligibility and carries the
  returned execution fence into ResultContext. Properties invalidation uses the
  Score Owner after APPLIED facts writes; never fan out to Candidate Caches. Unmatched and unselected holds expire naturally;
  do not compensate-release them or add a pending lease registry.
  PRECOMPUTED and ON_DEMAND are mutually exclusive fixed workflows; do not add
  a generic acquisition Strategy, Cache exchange or cached-to-on-demand fallback.
- `WorkerMatchQueue` is the complete PRECOMPUTED handoff contract. Pacer
  offers, Matching consumes, and health reads size through the same Queue
  interface; do not split producer and consumer operations behind another
  Runtime or write-only port. The current Server assembly selects the bounded
  in-memory implementation, but Pacer and Matching must not depend on that
  storage choice. Queue size is diagnostic, while `offer` is the admission
  result.
- It does not own Redis keys, mechanical owner state, Spring assembly, HTTP or
  deployment.
- Do not add a Pacer SPI, dynamic registry, further public internal Pacer type,
  reflection, ServiceLoader or a second external runtime entry.
- Prove policy behavior through focused Pacer tests and Redis-sensitive
  behavior through the stable owner ports.

## Server JVM

[Server Owner](server_jvm/README.md) owns API, assembly and use-case details.

`server_jvm/` controllers and services depend on `kernel_jvm` and
`worker_matching_jvm` owner contracts; Spring assembly additionally depends
only on the public `kernel_pacer_jvm` runtime entry. Provider selection belongs
only to assembly.

Server may own:

- public API validation and error mapping;
- bounded use-case orchestration;
- Worker external Identity, local Endpoint defaults and Prepare orchestration;
  Kernel WorkerResourceCatalog owns persistent Group/Endpoint Binding.
- create-only WorkerGroup registration and bounded Runtime projections;
- DIRECT_CALL admission and request correlation;
- bounded Worker Serviceability request/result routing without score policy;
- configured Adapter startup and create-only advisory WorkerGroup seeds.

Server persists PRECOMPUTED Matching Rules before Kernel Task metadata.
Worker Prepare resolves identity and asks Kernel to establish Binding and cold
Score membership in separate, retryable stages. Valid network evidence requests
activation best-effort in every Pacer preset; Prepare itself is not evidence.
It must not create or refresh Matching Properties, including for new Workers.
First and later Worker facts enter through the same Adapter observation and
Server admission path. Delivery reception owns that complete bounded use case,
including Binding validation and grouped Matching writes; it must not merge
observations with old Worker facts. Platform Properties management is independent
and cannot modify the Worker Properties Map. Runtime Views may expose identity before facts exist,
but an empty display must never become a stored baseline or readiness claim.
Server does not interpret Rules or select Workers.

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

- Rule/Properties interpretation, candidate matching or Worker selection;
- scheduling lease, Item claim, retry, recovery or Task finality;
- Adapter queues, Channels or current route selection;
- Worker business handlers or Worker lifecycle;
- Redis bypass around an owner contract.

DIRECT_CALL is a caller-targeted, best-effort Server use case. It does not
observe or change Worker score and creates no Kernel mode or strong lock.
Its only public call route is scoped by `adapterId`; an optional same-Group
`workerId -> opaquePayload` map supplies Worker targets and per-target input,
not a WorkerGroup authority.
Follow the [Server Owner](server_jvm/README.md) for bounded consume ordering
and Serviceability snapshot admission. Worker Direct Calls use the owner
`offer` operation and cannot replace an occupied slot; authoritative TASK
append may replace an unconsumed Direct Command.
Adapter-local Commands route by `dst`; only Worker Command map keys carry
workerId address meaning. Direct Call passes `messageType` and opaque payload
through without an event whitelist; future API Session authorization remains a
separate owner.

Profile contents and defaults belong to
[Server assembly](server_jvm/README.md#worker-and-scenario-assembly).

## Worker Delivery Contract

[Delivery Contract](transport/worker-delivery-contract/README.md) owns protocol DTOs and encoding.

`transport/worker-delivery-contract/` is Java 11 compatible and transport
neutral.

- `DeliveryCommand` target identity remains outside the DTO.
- `DeliveryReport` carries producer `src + sourceId`. Command messageType is
  intent; Report messageType is an event contract, never a Command-name echo.
  Required string diagnosticCode (including empty) is diagnostic only. Admission,
  correlation and result lanes use exact event names plus producer/target.
  Keep command results, delivery facts, observations and identity distinct;
  Report names are not callable Handler capabilities.
- SERVER identifies Server-owned Direct Call requests and replies; only SERVER
  Reports may complete its waiter. SYSTEM identifies platform events, whose
  message contract determines the semantic owner, not the HTTP host. The fixed
  Adapter Properties observation enters Matching only after Server admission;
  unknown SYSTEM events are rejected item-by-item and never complete Direct
  Calls. Do not alias the two.
- `forward` remains opaque until its downstream owner.
- Do not add Server, Kernel, Redis, Netty, Android or scheduling dependencies.
- Long-lived connections use an Adapter-directed identity Report followed by
  direct Command/Report JSON; do not add a third connection envelope.

## Netty Adapter

[Adapter Owner](transport/netty-adapter/README.md) owns production composition,
queue bounds, cache rules and shutdown sequence.

For implementation hardening, preserve the aggregate, fixed Process Manager,
Command and Report Dispatchers, connection mechanism/route Registry, and
complete physical Server boundaries. Apply the migration rule above when the
user explicitly requests changing this owner cut.

Rules:

- The aggregate owns public lifecycle and network shutdown ordering.
  `AdapterProcessManager` owns exactly two same-lifetime Dispatchers and
  their shared join deadline; it is a fixed composition, not a dynamic list.
- The Command Dispatcher owns the bounded retry Queue, resident thread,
  stop intent, batch acquisition and retry policy.
  `DeliveryCommandProcess` processes one batch once and owns no Queue,
  thread, sleep, pending batch or lifecycle. Preserve retry/fresh-batch progress
  through the [Command loop contract](transport/netty-adapter/README.md#command-consumption-loop).
- `DeliveryReportDispatcher` owns four finite TASK, SERVER, SYSTEM and KERNEL
  `LinkedBlockingQueue<DeliveryReport>` lanes, their non-blocking admission,
  rotating homogeneous batches, destination failure policy, and exactly one
  resident daemon platform thread. Queue count does not determine thread
  count. The four lanes do not cross the Report owner boundary. SERVER, SYSTEM
  and KERNEL admission-drop diagnostics must remain aggregated rather than
  logging once per Report.
- One process-scoped Adapter Factory owns the immutable Remote API facade and
  codec used by every Adapter it creates. The facade owns the fixed Command
  consume and Report append paths, wire JSON and method-specific status
  semantics. Its process-shared
  JDK HTTP client and explicit virtual-thread executor own raw HTTP resources
  only and carry no Adapter configuration or lifecycle. Do not restore the
  JDK client's default cached platform-thread executor.
- One flat public Adapter config is the complete construction input. Server
  binds it directly and checks only the matching Endpoint; the Factory
  destructures it so internal owners receive only their own values. Do not
  restore tagged Process lists, cache config wrappers, Server-side JSON schema
  parsing, global singleton configuration or compatibility aliases.
- Preserve the [Report loop contract](transport/netty-adapter/README.md#result-ingress-loop):
  bounded per-lane admission, aggregate memory accounting, a physical TASK
  retry reserve and bounded homogeneous batches through one append path.
  TASK remote unavailability requeues the exact batch at the tail without a
  retry count or deadline; SERVER, SYSTEM and KERNEL submission failures drop
  their batch. Delivery may lose or duplicate evidence. Shutdown drops current
  and queued Reports without a final synchronous flush.
- Server rejects a mixed or unsupported Report batch before semantic Owner
  side effects, then routes the homogeneous batch by `dst`. New Report classes
  extend the Adapter lane and Server Owner switch, not the HTTP path.
- Future Report concurrency may use only one bounded executor inside the
  Report owner with explicit in-flight, retry-reserve and shutdown bounds; do
  not create one thread or pool per Queue.
- Connection mechanism owns identity interpretation, first verification,
  current route use and valid Result ingress. Registry owns route truth.
- First verification checks existing Kernel Binding and does not register or migrate
  a Worker. First verification crosses only the injected single-item
  `WorkerRouteVerifier` port. Its Server batch owner may coordinate a bounded
  queue and bounded Binding reads, but neither side may expose
  Channel or Route state through that port. Verification has no HTTP endpoint.
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
  it must not be folded into RouteEntry or stored as another Server/Kernel truth.
  Publishing observations crosses SYSTEM and Server admission; Adapter must
  not access Matching storage directly.
- Worker Properties observation must come from the exact current verified
  Channel. The fixed `properties.updated` and `properties.replaced` events carry
  direct string KV Maps: update merges supplied keys into a full baseline;
  replacement atomically replaces the Map, deleting omitted keys. Missing
  baseline and invalid updates are local drops; empty strings are not deletions.
  Explicit TASK/SERVER snapshot Results keep their wrapped query payload and
  never write the cache. Keep cache content immutable
  with owner-local fingerprint and observation metadata, without field versions.
  After installation and current-Channel recheck, publish the complete Map once
  through SYSTEM. Queue/HTTP failure leaves the cache intact and does not close
  the Worker, retry, or create pending publication state. Server validates
  Binding/Group and Matching owns full persistent replacement. Host publication
  policy must not become a Core scheduler or an ACK/recovery prerequisite.
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
- Only valid bound Worker TASK/SERVER/SYSTEM evidence follows the current destination
  rules; invalid unbound input and TASK result backpressure may close the exact
  connection.
- Expired TASK delivery independently offers its correlated
  `platform.adapter.command.delivery-failed` TASK Report (diagnostic 23002,
  workerId and DEADLINE_EXCEEDED reason) and a
  separate `platform.adapter.worker-delivery.expired` KERNEL Report to their
  Report lanes; Transport does not interpret either as score policy and does
  not promise cross-lane atomic admission.
- Shutdown waits are owner-local and bounded. Do not reset spent deadlines or
  add unbounded waits.
- Do not add Session, protocol SPI, dynamic Process/lane registry, reflection,
  ServiceLoader or an in-process Server shortcut.

`transport/netty-adapter` must not depend on Server, Kernel, Spring, Redis or
Pacer implementations. Server may depend only on its public finite factory and
the `WorkerDeliveryAdapter` contract.

## Worker Core And Platform Workers

[Worker Core](transport/worker-core/README.md), [Java Worker](transport/java-worker/README.md) and [Android Worker](transport/android-worker/README.md) own their mechanisms.

`transport/worker-core` is Java 11 platform-neutral mechanism code.

- Core depends only on the delivery contract.
- A reporting Handler may retain its run-bound WorkerOutcomeReporter after
  synchronous completion. Host owns association and cleanup; Core keeps no
  reporter registry, queue, thread or retry. Non-TASK calls cannot report later
  outcomes, and a closed run's Reporter cannot transfer to a new run.
- Client owns networking and transparent reconnect.
- Transport owns identity/Command/Result protocol and synchronous event
  execution.
- `WorkerRunController` owns only the `RUNNING/STOPPED` run lifecycle.
- Follow the [Worker run contract](transport/worker-core/README.md#one-worker-run)
  for complete Properties loading, single Prepare and optional Manager-prepared
  starts. Core owns neither batch HTTP nor Host Properties aggregation.
  [Server preparation](server_jvm/README.md#workergroup-and-worker-preparation)
  owns Worker kind and identity policy; Core and Manager do not interpret it.
  Workers must not persist or hint workerId. Worker kind selects a typed
  registration-key algorithm, never a Redis key address; one Group retains one
  identity Hash without algorithm aliasing. Transparent reconnect sends identity
  without re-Prepare. Explicit Properties reports go through Adapter observation
  and Server admission, never a direct Matching write or automatic Core
  publication scheduler.
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

Java Worker Manager may accept immutable per-replica extension Definitions at
construction alongside Group-common Definitions. Both pass through the same
reserved-default and duplicate Event Name checks. This adds no runtime Handler
registration, identity inference, thread or Core API.

## Scenario And Android Capabilities

[Scenario Host](scenario_workers_jvm/README.md) and [Android modules](xa-android/README.md) own inventory, capability and lifecycle details.

`scenario_workers_jvm` is a finite standalone Java 21 Lab Worker Host, not a
Kernel owner, Server profile, Adapter, production Worker platform or plugin
system.

- It may depend on Worker Core and Java Worker, not Kernel, Server, Adapter,
  Redis, reflection or configurable class names.
- It owns local capability definitions, persistent Lab files and one
  `JavaWorkerManager` per configured non-empty WorkerGroup.
- Preserve the [Lab inventory contract](scenario_workers_jvm/README.md#persistent-worker-lab)
  for schema, string Properties, per-file and per-Group bounds and immutable
  physical identity coordinates. `labWorkerKey` is Lab-local, not a universal
  Worker identity field. Scenario uses the Server-owned `SCENARIO_LAB` Prepare
  policy; ordinary Prepare remains available to other Java and Android Workers.
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
  The Host binds this listener before starting outbound Worker connections and
  begins serving only after initial startup completes, so the configured port
  remains owned even when the loaded-recovery proof widens the OS ephemeral
  port range.
- Atomic Worker file replacement fails closed when the filesystem cannot honor
  `ATOMIC_MOVE`. A start issued while the Manager's previous stop is still
  converging is a conflict; callers observe `STOPPED` before retrying.
- Every explicit Worker start reopens its complete Properties file. There is no
  watcher, automatic reconcile, dynamic inventory, or generic fault DSL.
- Lab `:properties` PATCH/PUT may persist and explicitly publish through the
  existing Manager during a running Worker run. Preserve immutable inventory
  coordinates and file-only PUT semantics. Use one non-queuing per-Worker gate
  across persistence/publication, including the file-only PUT; retain serialized
  file writes and keep SDK sends outside the inventory monitor. Stop/shutdown
  must not wait for publication. Local acceptance is not remote ACK; never
  compensate, retry or add pending publication state.
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

[Proof Registry](doc/testing/proof-registry.md) identifies Primary Owners; each Integration README owns its world, thresholds and complete scenario.

- Integrations call public Runtime APIs and must not import Server, Kernel,
  Adapter or Worker implementations.
- [Android Worker Proof](integrations/android-worker-proof/README.md) owns real
  Android assertions in Java. Its shell owns only external process choreography.
  Device-local state establishes mutations; public Network, Scheduling, Direct
  Call and Task observations independently establish system effects. Retry only
  temporary HTTP observation failures; invalid contracts and identity drift
  fail immediately. Never assert business Result payloads or promote disposable
  Emulator controls into background-survival evidence. This lane is not a Java
  Worker witness; new platform claims require an explicit scenario boundary.
- One-shot Python proof runners may own process orchestration, but not database
  protocols. Redis scope cleanup uses
  `.github/scripts/cleanup_redis_test_scope.py` and `redis-py`; do not add a
  lane-local RESP client. Unique `test_*` scope is the proof isolation
  boundary; cleanup is best-effort local resource hygiene and must not replace
  the proof outcome. GitHub jobs with disposable Redis Services explicitly
  skip it. Keep one runner entrypoint per high-level proof lane rather than
  hiding distinct failure sequences in a generic scenario runner.
- Worker Convergence Health may combine the loopback Lab API with independent
  Runtime Preview, Network, Scheduling, and finite Task APIs. Its evidence may
  record identities and projected states but never business payload. Failed or
  ambiguous Lab operations are non-evidence, not Adapter or Kernel failures;
  exact Network and Scheduling observations page the bounded Runtime API.
  Runtime Preview is only a per-Group sample, never fleet enumeration.
  Each deterministic scenario stops mutation injection and evaluates the
  actual observed local world instead of installing a preferred final world.
  It uses managed ON_DEMAND batch calls as offered load and `results:load` only
  for named witnesses. It must not turn `NOT_OBSERVED` into failure, require all
  offered Items to succeed, count `FAILED` as a successful witness, poll
  `results:export`, or broaden the separate PRECOMPUTED Properties witness into
  repeated topology claims owned by Runtime Boundary.
- [Worker Loaded Recovery](integrations/worker-loaded-recovery/README.md) owns
  sustained-load, repeated-recovery and resource-stability claims in its separate
  nightly/manual Linux lane. Keep Runtime API observations bounded, retain Host
  identity across Server failures, and export each terminal Task once. Mutations
  require the scenario's established loaded-work preconditions. Connection
  availability during work and connected/HOT convergence after drain are
  different oracles; stopped identities must remain present and inactive.
  Resource evidence must cover transient ceilings and stable drift. Do not
  infer Task fairness, completion order, Handler concurrency, throughput,
  latency or soak from this lane.
- [Worker Call Performance](integrations/worker-call-performance/README.md) owns
  offered-load, completion and latency evidence in its separate nightly/manual
  workflow. Keep HTTP acceptance, successful Result observation, unknown
  submission and generator limitations distinct. Fixed Worker counts are
  fixtures, not extra proof levels. JVM Contracts owns only deterministic
  Harness/runner tests; Redis Owner retains mailbox concurrency and command-cost
  proof. Aggregate Redis diagnostics never replace public-API assertions.
- Worker Correctness inputs are caller-owned local files. Its perfect-world
  proof uses managed batch `items:call`, fixes exact Item statuses and treats
  Result payload as opaque. The frontend separately turns lines into ordinary
  finite TaskItems through public Task APIs; Server owns no Lab input/output
  directory.
- Worker Correctness live Properties mutations use Lab HTTP and the real
  Scenario Host/SDK connection before its existing Host restart phase. Java
  observes Adapter/Server through public APIs; it must not call implementations
  or inject Reports. The runner owns process identity, control-file and initial
  Prepare/access-log audits. Fixed budgets and full snapshot oracles belong in
  the Integration Owner document. Keep Lab Properties and private phase data
  outside the CI artifact whitelist; local Harness success requires the runner
  audit before it can become phase success.
- [Worker Dynamic Matching](integrations/worker-dynamic-matching/README.md) owns
  loaded PRECOMPUTED execution under live Worker/Platform Properties changes.
  Scenario's optional execution witness must capture the actual Group/replica
  in the construction-time Handler closure; request tokens only correlate.
  Keep its finite paginated journal explicit on overflow and outside artifacts.
  Harness assertions use Lab and public Runtime APIs; runner audits establish
  unchanged processes, control files and no new Prepare. Existing Owner proofs
  retain dirty/confirmation races, separate commits and fault-delivery limits.
- Frontend is read only for Runtime truth. Its finite Task file flow may create,
  append, approve, and export only through public Task APIs and must not infer
  scheduling state from elapsed time.
  Its single-Worker Direct Debug action may invoke only the public
  Adapter-scoped DIRECT_CALL API. That action remains caller-targeted and
  best-effort; its response must not be promoted to schedulability, capability,
  Worker identity or lifecycle truth.
- `frontend/public/overview.htm` is a human projection. Current truth remains
  in executable and owner documents.
- `distribution/server` is a packaging owner only. It assembles the current
  Server, production Pacer, frontend and configuration. It must not package the
  repository-local Scenario Worker Host, add a fallback runtime owner, add a
  second production mechanism or introduce scheduling behavior.

## Verification

Use [TESTING.md](TESTING.md) for claim-based proof selection and commands.
Behavior changes require the focused Owner lane; Redis and runtime claims
require their named real-infrastructure proof. Documentation-only changes use
Docs Contract and link/content review without selecting runtime proofs. A
document that describes a runtime mechanism is still a documentation change.

Before completion:

- run `git diff --check`;
- scan affected references when removing or renaming names, routes or files;
- confirm archive material is not linked as current truth;
- report which checks actually ran and any required proof that could not run;
- distinguish source inspection from executed proof, and submitted changes from
  externally applied results. Do not claim completion while a required external
  step remains pending; finish the independent authorized work and state the
  remaining dependency.
