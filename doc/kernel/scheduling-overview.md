# Kernel Core Scheduling

Status: current Java scheduling mechanism and production policy index.

The workspace-level isolation, event-cost, liveness, and score-writer rules are
defined in [Kernel Core Design Workspace](../../README.md). This file is the
entry map and single current status matrix for scheduling owners and their
executable mechanisms; individual documents below own detailed invariants.

## Scheduling Planes

The kernel has four scheduling planes:

| Plane | Owner question | Input | Output | Truth it may mutate |
| --- | --- | --- | --- | --- |
| Task score-band | Which Tasks may enter a scheduling round now? | Task score coordinate and owner facts | Bounded Task ids or an owner-approved score transition | Task scheduling score only |
| Worker score-band | Which Workers may enter admission or recovery now? | Worker score coordinate and worker-runtime facts | Bounded Worker observations, leases, holds, or polarity movement | Worker scheduling score only |
| Assignment-dispatch | Which bounded Task/Worker/Item combination becomes delivery evidence? | Due Tasks, Task descriptors, policy-selected Worker acquisition, TaskItem records and Item scores | Optional CandidateWorker cache evidence and Adapter-partitioned DeliveryCommand mailboxes | Candidate cache plus TaskItem score through declared owner primitives |
| Result-routing | How does returned evidence affect Task success finality and Worker disposition? | DeliveryReports and opaque result context | Owner-local Task and Worker evidence delegated to policy handlers | No private truth; handlers invoke TaskItem and Worker score owners |

These are logical planes, not mandatory deployment modules. Package placement
may change without changing owner authority.

`TaskItemScoreBandCore` is a separate score owner used by assignment-dispatch
and result-routing. It is not collapsed into Task score and is not a fifth
process lifecycle.

All three score owners follow the shared
[Three Score Axes](../../README.md#three-score-axes) contract:

```text
Task      lifecycle moves to lower tags; negative is immutable terminal
Worker    sign is scheduling serviceability: HOT_ACQUIRE / RECOVERY_RECHECK
TaskItem  outcome moves to higher tags; ACTIVE claim time only moves forward
```

They share ordered discovery, owner-minted coordinates, opaque score fences,
and bounded numeric transition primitives. They do not share one tag direction,
one suffix meaning, or one universal delta constant. Each axis derives the
simplest safe numeric relation from its own encoding; Redis/Lua does not need
business event names or caller-supplied score ranges.

For every mutable lane, `timeSlot` is the next time that lane may act and the
low-order coordinate is lane-local ordering or state. Priority and rank ranges
may differ by lane, but smaller numeric values always mean earlier priority.

## Mainline

```text
Task score acquire
  -> approve soft-limit precheck, then exact RUNNING INITIAL transition
  -> due-Item initialization check
  -> exact transition to RUNNING NORMAL
  -> optional RUNNING candidate cache warming through HOT-pool acquisition
  -> Task Dispatch
     -> TaskItem observation
     -> Task-scoped PRECOMPUTED or Item-scoped DIRECT candidate acquisition
     -> TaskItem exact claim
     -> direct DeliveryCommand construction
     -> endpointManagerId-partitioned sparse DeliveryCommand mailbox
  -> Worker Delivery Dispatch
     -> target Worker point poll through system-polling or another binding
     -> long-lived Adapter bounded batch consume for active push transports
     -> accept Worker point results or Adapter result batches
     -> DeliveryReport queue
  -> Result Routing
     -> 200: store last-success + FINAL_SUCCESS + completed-HOT exact release
     -> Worker failure: keep Item claim coordinate + Worker exact release
     -> Adapter rejection: keep Item claim coordinate + Worker exact release
     -> no result: Item claim and Worker lease expire naturally

Auxiliary Server direct path
  -> caller-selected Workers bound to one Adapter
  -> non-overwriting offer into the shared Worker Command Hash
  -> instance-local Adapter FIFO and aggregate waiter
  -> the same Adapter commands:consume / results:append boundary
  -> SYSTEM Worker Definition or fixed Adapter-local control
  -> no Task, TaskItem, lease, or Result Routing truth
```

The public WorkerAllocationMechanism split is fixed:

```text
PRECOMPUTED_TASK_RULE = Task rule + PRECOMPUTED Worker acquisition + candidate cache
DIRECT_ITEM_RULE = TaskItem rule + DIRECT Worker acquisition + no candidate cache
```

`TaskIdleDisposition` independently selects immediate `CLOSE_WHEN_IDLE` or the
Kernel-private `PARK_WHEN_IDLE` coordinate. WorkerAllocationMechanism establishes
no ordering between Tasks: priority, latency target,
RPC versus batch shape, arrival density, and Worker contention policy are
orthogonal scheduling inputs or workload properties.

This is a summary of the canonical
[Task Resource Model](../../kernel_jvm/doc/resource-model/task-resource-model.md)
contract.
Generic public Server Task creation exposes only
`PRECOMPUTED_TASK_RULE + CLOSE_WHEN_IDLE`. WorkerGroup registration provisions
the fixed managed Task Call
`DIRECT_ITEM_RULE + PARK_WHEN_IDLE` assembly. Neither surface exposes an
arbitrary mechanism matrix.

WorkerAllocationMechanism is the allocation boundary. It does not define a
public Cartesian product of cache, acquisition, trigger, fairness, or retry
modes. Scheduling tests therefore prove the
`PRECOMPUTED_TASK_RULE` and `DIRECT_ITEM_RULE` vertical paths plus owner-local primitives,
instead of manufacturing unsupported policy combinations.

The score axes and bounded runtime handoffs provide liveness. Events may provide
business evidence or reduce latency, but they are not required scheduling
triggers. Detailed mutation authority remains in the owner documents below.

## Owner Boundaries

```text
TaskScoreBandCore
  owns Task acquisition visibility, encoding, bounded queries, holds,
  lifecycle-direction validation, and terminal score

TaskInitializationCheck
  receives the bounded taskId-to-opaque-score INITIAL subset, checks due ACTIVE
  Items once, and asks the Task Score Owner for one exact batch promotion

TaskRuntime
  owns Task descriptors, canonical TaskItem records, and Task-scoped
  last-success result payloads

TaskItemScoreBandCore
  owns Item initialization, bounded ACTIVE acquisition, observed same-tag
  claim/retry rewrites, and monotonic final outcome promotion

WorkerScoreCore and worker-runtime
  own Worker acquisition/recovery coordinates, exact leases, dirty fence,
  canonical resource Properties and candidate validation

CandidateWorkerCache
  owns transient CandidateId-local candidate evidence only

DispatchMainScheduler
  reads one bounded taskId-to-opaque-score map, asks the Task Score Owner for
  its INITIAL subset, loads Descriptors only for the NORMAL complement, and
  plans the complete root input of four fixed single-flight Resource Producers

WorkerCandidateMatcher
  owns bounded canonical descriptor pre-filter and post-lease rematch
  produces the final endpoint-bearing AcquiredWorkerCandidate

TaskAssignmentDispatcher / TaskIdleSettlement
  protect the two real cross-Owner Task Dispatch closures: Worker renew before
  Item claim before Command publication, and complete ACTIVE recheck before
  exact close or park repair

WorkerServiceabilityDispatchPolicy
  directly composes bounded Worker Score, canonical Descriptor, and Probe
  Request Owner operations; it visits demanded WorkerGroups in Task order
  under one 100-Probe hold budget

WorkerCommandRuntime
  owns sparse Adapter HASH mailboxes, authoritative append, non-overwriting
  offer and destructive consume; the mailbox is not lifecycle truth

TaskResultRuntime
  owns two bounded best-effort Task result-class queues, not Item or Worker truth

ResultConvergenceApplication
  owns one weighted-fair coordinator and ten shared asynchronous Batch slots;
  fixed per-lane target/max policy lets both Task lanes borrow capacity while
  optional Adapter Evidence remains single-flight; it owns no Redis key or
  result policy

TaskResultBatchPolicy
  decodes and groups one already-classified homogeneous Task result Batch and
  publishes bounded TaskItem and Worker execution semantic events; it receives
  only opaque Worker lease references and calls no score owner

WorkerServiceabilityRuntime
  owns Adapter-partitioned coalesced probe requests and one bounded batch
  Report handoff; neither structure is current connection or score truth

WorkerServiceabilityDispatchPolicy / ResultPolicy
  consumes Main-planned demanded Groups and translates valid Adapter evidence
  into finite semantic callbacks; the Result policy does not read or mutate
  Worker score

Fixed Result semantic Mechanism ports
  compose legal Task, TaskItem and Worker owner operations behind named event
  methods; they are not a registry, SPI or replacement-handler surface

DispatchConvergenceApplication
  owns one Main Scheduler and fixed single-flight Resource Producers; the Main
  Scheduler plans every root input and busy Producers skip without creating a
  replay hint

Worker Delivery Dispatch
  Server exposes point/batch access to already-assigned commands and semantic
  result ingress; a finite factory creates independently isolated Adapter
  instances with three top-level owners: an Adapter aggregate owns start/close
  and network ordering, while its `AdapterProcessManager` owns the fixed
  Command/Report Process set and its single scheduler; one
  shared connection mechanism plus route
  Registry owns one process-local per-Worker route state and Channel-local
  identity correlation, routing, and Result ingress; one complete WebSocket or line-Socket
  Server owns the listener, EventLoop, all physical child Channels, full
  Pipeline, framing, writes, and close behavior. Server only
  binds instance config and invokes lifecycle; neither path selects Workers
  nor mutates score
```

## Mechanism Status

| Mechanism | Executable status | Owner-local deferred policy |
| --- | --- | --- |
| Task score-band | Implemented with Redis proof | Cadence, scan horizons, and no-work budget values |
| Worker score-band | Implemented with Redis proof, including dirty lease fence | Dirty marking policy when a persisted assignment continuation exists; recovery cadence and ranking |
| Worker HOT_ACQUIRE lease protocol | HOT-pool precomputation, DIRECT bounded Group-or-point lease-match, PRECOMPUTED exact recheck-rematch, exact result release, and one-WorkerId/one-slot invariant implemented | Serviceability polarity is owned by the independent evidence Pacer |
| Worker serviceability | Process-local HOT eligibility floor, exact pre-Probe Score hold/Recovery advance, Adapter Route/delivery-expiry evidence, Adapter-scoped request HASH, bounded evidence LIST, due-Task-driven compensation Dispatch Pacer, lowest-priority Adapter snapshot bridge, and time-fenced polarity-only Result convergence implemented; absent configuration preserves the old HOT range | Polling wake/evidence, Binding generation fencing, and production policy tuning |
| TaskItem score-band | Java Owner and Redis provider implement append, bounded ACTIVE observation, exact claim and final promotion | Initial retry budget and claim-duration values |
| Task initialization | Implemented inside RUNNING with one fixed INITIAL time slot and an Owner-derived priority suffix, a best-effort 100-Task approval soft limit, due-Item check and exact INITIAL-to-NORMAL promotion | Additional explicit start conditions or strict capacity, if a future invariant proves either is needed |
| Worker allocation | Implemented as a Main-planned PRECOMPUTED Task Resource Producer that fills candidate deficits through direct bounded Candidate Cache and Worker Score Owner calls; it does not discover or mutate Tasks | Candidate ranking beyond bounded due order and matcher priority |
| Task dispatch | Implemented over the same verified RUNNING batch with PRECOMPUTED Task rules, DIRECT Item rules including `{}` as Group-unrestricted, stable Item binding, RUNNING pacing, immediate idle close or private idle park, and DeliveryCommand append | Recent-first Redis Task acquisition |
| Worker Delivery Dispatch | Shared Java Worker Delivery contract, Server point/batch HTTP API, Server-owned persistent Endpoint Binding, complete multi-endpoint WebSocket/Socket Adapter instances with workerId-keyed bounded retained-verification caches, stateless bounded batch acquisition, fixed system-polling route, Java 11 Worker Core Polling/WebSocket/Socket state machines, caller-targeted DIRECT_CALL using a shared Worker Command Hash plus Server-memory Adapter FIFO/correlation, and the low-priority KERNEL Adapter-snapshot bridge | Authentication, distributed Direct Call waiter state, explicit unbind/cache invalidation, endpoint migration, same-endpoint Adapter HA, pending/ack, polling Serviceability evidence, and production protocol policy |
| Result routing | Fixed Java production policy implemented with unit, Redis and Runtime Boundary proof; Java exposes bounded last-success reads | Failure/history projection and stronger queue reliability require separate owners and invariants |

Both WorkerAllocationMechanisms also have Runtime Boundary Redis E2E proof from
Java control and Task data APIs through Java scheduling and the Java Server
Worker Delivery API. `PRECOMPUTED_TASK_RULE` uses Worker Core's polling transport;
`DIRECT_ITEM_RULE` uses independent Netty WebSocket/Socket Adapter endpoints and
the matching Worker transports. Tests install a local observable handler
rather than a framework-owned business handler. All paths converge through
Result-Routing, `FINAL_SUCCESS`, Java last-success query, and exact Worker
lease release. Java controllers and Pacers use the same stable Kernel Owner
contracts. Task business commands use Java Redis providers, and production
uses Java for every Pacer.
Additional Redis proofs cover immediate idle close, private idle park followed
by scheduling-aware Task Call submission, complete RUNNING soft-limit
observation, and an external explicit close request.

Deferred policy stays in the document of the mechanism that consumes it. There
is no global policy backlog document and no policy residue may create a second
runtime path.

## Current Scale Boundaries

The Java Kernel deliberately uses a small number of Redis Owner keys:

```text
xa_mass:<scope>:task:score
  one global Task score ZSET

xa_mass:<scope>:worker:score:<workerGroupId>
  one Worker score ZSET per WorkerGroup

xa_mass:<scope>:task:<taskId>:item_score
xa_mass:<scope>:task:<taskId>:items
xa_mass:<scope>:task:<taskId>:results
  Task-local Item score and record/result HASHes

xa_mass:<scope>:delivery:commands:<endpointManagerId>
  one shared sparse DeliveryCommand HASH per Adapter route; TASK append may
  replace, while a generic offer fills only an empty worker field

xa_mass:<scope>:result:routing:<success|failure>
  two global best-effort Task result LISTs

xa_mass:<scope>:worker:serviceability:adapter:<adapterId>:probe_requests
xa_mass:<scope>:worker:serviceability:evidence_results
  coalesced Adapter route-probe requests and bounded Adapter Report evidence

```

These are current mechanism boundaries, not claims of unlimited throughput.
The supported production shape is deliberately vertical:

```text
small bounded active Task set
  -> many TaskItems per Task
  -> many Workers inside a finite WorkerGroup set
```

One hot Task is therefore an expected load shape. Backpressure belongs in its
bounded Item observation, Worker selection/lease, Item claim, DeliveryCommand
and Result convergence chain.

The Kernel liveness target is **work-conserving convergence**, not per-Task
fairness:

```text
compatible Workers fully occupied + assigned work keeps completing
  -> normal backpressure

compatible capacity temporarily idle during bounded scan / exact CAS /
Candidate refill
  -> normal convergence delay

compatible capacity persistently idle + TaskItem persistently due +
no assignment across repeated eligible rounds
  -> scheduling starvation / liveness defect
```

A full bounded Task page is therefore not enough to classify starvation. If
its compatible Workers are fully utilized, waiting Tasks need no fairness
repair. If compatible capacity remains idle, repeated Redis rediscovery must
eventually produce assignments; code review and focused proofs should follow
that vertical path rather than inventing Task rotation. Massive active
Task/WorkerGroup cardinality, tenant-level fairness, sharding and SaaS-scale
isolation require a separate architecture and must not be inferred as current
Pacer obligations. Adding Pacer or HTTP threads does not partition a hot Redis
key and may only increase duplicate observation and exact-CAS contention.

The polling API performs only point mailbox consume for one target Worker. It
never scans a bucket. Each locally registered Adapter instance may
consume one bounded random batch from its sparse bucket through the Server
batch HTTP API and
serve Workers through an independent Netty listener. The Adapter runtime owns
its scheduler, command consume bound, common route/connection mechanism, every
child Channel, current bound-route selection, bounded delivery,
Adapter-rejection/`UNKNOWN`, and Report-buffer policy. Its selected network
protocol owns only physical framing and close. The Server host only binds
configuration and forwards process lifecycle events.
Destructive prefetch failure remains `UNKNOWN` without pending/ack.

## Core Reading Path

After this index, a new agent can understand the current scheduling mainline
from these documents:

1. [Task Resource Model](../../kernel_jvm/doc/resource-model/task-resource-model.md)
2. [Assignment-Dispatch Scheduling](../../kernel_pacer_jvm/doc/dispatch/assignment-dispatch-scheduling.md)
3. [Worker HOT_ACQUIRE Lease Protocol](../../kernel_jvm/doc/score/worker-hot-acquire-lease-protocol.md)
4. [Task Dispatch Pacer](../../kernel_pacer_jvm/doc/dispatch/task-dispatch-pacer.md)
5. [Result-Routing Scheduling](../../kernel_pacer_jvm/doc/result/result-routing-scheduling.md)
6. [Worker Serviceability Scheduling](../../kernel_pacer_jvm/doc/dispatch/worker-serviceability-scheduling.md)

Read owner details only when changing that owner:

- Score axes:
  [Task](../../kernel_jvm/doc/score/task-score-band-scheduling.md),
  [Worker](../../kernel_jvm/doc/score/worker-score-band-scheduling.md), and
  [TaskItem](../../kernel_jvm/doc/score/task-item-score-band-scheduling.md).
- Initialization and candidate preparation:
  [Task Initialization](../../kernel_pacer_jvm/doc/dispatch/task-initialization-policy.md)
  and
  [Task-Worker Allocation](../../kernel_pacer_jvm/doc/dispatch/task-worker-allocation-pacer.md).
- Process and transport:
  [Kernel Application Assembly](../../kernel_pacer_jvm/doc/application-assembly.md),
  [Worker Delivery Dispatch](worker-delivery-dispatch.md), and
  [JVM Runtime API Server](../../server_jvm/README.md).
- Backend representation:
  [Worker Runtime Redis Shape](../../kernel_jvm/doc/runtime-redis/worker-runtime-redis-shape.md)
  and
  [Task Result Runtime Redis Shape](../../kernel_jvm/doc/runtime-redis/task-result-runtime-redis-shape.md).

The score documents own encoding and transition rules. The Worker lease
protocol owns the one cross-pacer lease lifecycle without becoming a score or
runtime owner. The assignment parent owns the three-pacer protocol. Each pacer
document owns its round sequence, limits, stale/failure behavior, and deferred
policy. Redis shape documents own backend representation only.

## Production Code Map

```text
kernel_jvm/src/main/java/com/xa/mass/kernel/
  assignment/ delivery/ score/ serviceability/ task/ worker/

kernel_pacer_jvm/src/main/java/com/xa/mass/kernel/pacer/
  KernelPacerRuntime
  result/
  dispatch/
```

## Guardrails

- Do not merge Task, TaskItem, Worker, candidate-runtime, or result evidence
  truth to reduce the number of calls.
- Do not make an external event the only scheduling or recovery trigger.
- Do not let a pacer decode or mint another owner's opaque score evidence.
- Do not let Worker Delivery Dispatch select Workers or Result Routing refresh
  Task score.
- Do not make transient candidate or DeliveryReport queues durable lifecycle truth.
- Do not add a hot-path index, scanner, lock, queue, or transaction without a
  named owner invariant and bounded cost.
- Do not preserve superseded mechanisms as historical alternatives in active
  documents.
