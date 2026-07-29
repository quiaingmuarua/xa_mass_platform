# Kernel Core Scheduling

Status: declared scheduling mechanism baseline implemented in the Python
executable spec; policy coverage partial.

The workspace-level isolation, event-cost, liveness, and score-writer rules are
defined in [Kernel Core Design Workspace](../../README.md). This file is the
entry map for scheduling owners and their current executable mechanisms.

## Scheduling Planes

The kernel has four scheduling planes:

| Plane | Owner question | Input | Output | Truth it may mutate |
| --- | --- | --- | --- | --- |
| Task score-band | Which Tasks may enter a scheduling round now? | Task score coordinate and owner facts | Bounded Task ids or an owner-approved score transition | Task scheduling score only |
| Worker score-band | Which Workers may enter admission or recovery now? | Worker score coordinate and worker-runtime facts | Bounded Worker observations, leases, holds, or polarity movement | Worker scheduling score only |
| Assignment-dispatch | Which bounded Task/Worker/Item combination becomes delivery evidence? | Due Tasks, Task descriptors, policy-selected Worker acquisition, TaskItem records and Item scores | Optional CandidateWorker cache evidence and Adapter-partitioned WorkerCommand mailboxes | Candidate cache plus TaskItem score through declared owner primitives |
| Result-routing | How does returned evidence affect Task success finality and Worker disposition? | WorkerResults and opaque result context | Owner-local Task and Worker evidence delegated to policy handlers | No private truth; handlers invoke TaskItem and Worker score owners |

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
  -> ADMISSION Task/System policy evaluation
  -> RUNNING transition
  -> optional RUNNING candidate cache warming through HOT-pool acquisition
  -> Task Dispatch
     -> TaskItem observation
     -> Task-scoped PRECOMPUTED or Item-scoped TARGETED candidate acquisition
     -> TaskItem exact claim
     -> direct WorkerCommand construction
     -> endpointManagerId-partitioned sparse WorkerCommand mailbox
  -> Worker Delivery Dispatch
     -> target Worker point poll through system-polling or another binding
     -> long-lived Adapter bounded batch consume for active push transports
     -> accept Worker point results or Adapter result batches
     -> WorkerResult queue
  -> Result Routing
     -> 200: store last-success + FINAL_SUCCESS + Worker exact release
     -> 1xxx: keep Item claim coordinate + Worker exact release
     -> 3xxx: keep Item claim coordinate + Worker exact RECOVERY_RECHECK demotion
     -> no result: Item claim and Worker lease expire naturally
```

The public TaskType split is fixed:

```text
TASK_DRIVEN = Task rule + PRECOMPUTED Worker acquisition + candidate cache
ITEM_DRIVEN = TaskItem rule + TARGETED Worker acquisition + no candidate cache
```

Both TaskTypes use the same persisted empty-close threshold and bounded empty
recheck policy; TaskType selects Worker acquisition, not lifecycle finality.
TaskType also establishes no ordering between Tasks: priority, latency target,
RPC versus batch shape, arrival density, and Worker contention policy are
orthogonal scheduling inputs or workload properties.

This is a summary of the canonical
[Task Type And Allocation Rule](../resource-model/task-resource-model.md#task-type-and-allocation-rule)
contract. These dimensions are not independently selectable policies.

TaskType is the supported scenario boundary. Policy follows that scenario; it
does not define a public Cartesian product of cache, acquisition, trigger,
termination, fairness, or retry modes. Scheduling tests therefore prove the
`TASK_DRIVEN` and `ITEM_DRIVEN` vertical paths plus owner-local primitives,
instead of manufacturing unsupported policy combinations.

The score axes and bounded runtime handoffs provide liveness. Events may provide
business evidence or reduce latency, but they are not required scheduling
triggers. Detailed mutation authority remains in the owner documents below.

## Owner Boundaries

```text
TaskScoreBandCore
  owns Task acquisition visibility, encoding, bounded queries, holds,
  lifecycle-direction validation, and terminal score

TaskAdmissionPolicy and SystemAdmissionPolicy
  decide which bounded ADMISSION Tasks may request RUNNING; they own no
  score, Item, Worker, or candidate truth

TaskRuntime
  owns Task descriptors, canonical TaskItem records, and Task-scoped
  last-success result payloads

TaskItemScoreBandCore
  owns Item initialization, bounded ACTIVE acquisition, observed same-tag
  claim/retry rewrites, and monotonic final outcome promotion

WorkerScoreCore and worker-runtime
  own Worker acquisition/recovery coordinates, exact leases, dirty fence,
  resource metadata, dynamic attributes, and candidate validation

CandidateWorkerCache
  owns transient CandidateId-local candidate evidence only

CandidateWarmupSchedule
  owns disposable TASK_DRIVEN cache-replenishment hints only; it is not Task
  score, assignment, or liveness truth

TaskItemDispatcher
  owns one suffix-zero Task's bounded Item observation, Worker acquisition,
  exact Item claim, and WorkerCommand construction;
  it owns no Task score, mailbox, or background lifecycle

WorkerCommandRuntime
  owns sparse Adapter HASH mailboxes; the mailbox is not lifecycle truth

WorkerResultRuntime
  owns three bounded best-effort outcome-class queues, not Item or Worker truth

ResultRoutingPacer
  bounded-consumes, decodes, groups, and delegates owner-local evidence

ResultRoutingBuiltinPolicies or replacement handlers
  compose Task and Worker owner operations without owning their truth

Other scheduling pacers
  compose declared owner operations in bounded rounds; they do not copy truth

Worker Delivery Dispatch
  Server exposes point/batch access to already-assigned commands and semantic
  result ingress; the Adapter Runtime registers complete instances, each of
  which owns a Netty listener, start/close, scheduled batch consumption,
  current connection selection, bounded concurrent delivery, and result
  buffering. Server only binds instance config and invokes lifecycle; neither
  path selects Workers nor mutates score
```

## Mechanism Status

| Mechanism | Executable status | Owner-local deferred policy |
| --- | --- | --- |
| Task score-band | Implemented with Redis proof | Cadence, scan horizons, and no-work budget values |
| Worker score-band | Implemented with Redis proof, including dirty lease fence | Dirty marking policy when a persisted assignment continuation exists; recovery cadence and ranking |
| Worker HOT_ACQUIRE lease protocol | HOT-pool precomputation, TARGETED point lease-match, PRECOMPUTED exact recheck-rematch, result disposition, reconnect dirty fence, and one-WorkerId/one-slot invariant implemented | Recovery probe cadence and ranking |
| TaskItem score-band | Implemented with Python oracle plus JVM `TaskRuntime` append/last-success Redis-provider proof | Initial retry budget and claim-duration values |
| Task running activation | Implemented with due-Item Task policy and priority soft-limit System policy | Scenario-backed quota, tenant, business start condition, and resource-estimate decisions |
| Worker allocation | Implemented as hint-driven TASK-scope candidate cache warming through HOT-pool acquisition; Task score is read only for RUNNING/non-hard-pause suffix-zero validation; TASK_DRIVEN has deterministic Redis proof through cache consumption | Warmup prioritization beyond bounded due order and matcher priority |
| Task dispatch | Implemented with acquisition-only TaskType profiles, PRECOMPUTED Task rules, TARGETED complete Item rules, stable Item binding, RUNNING same-band reschedule, shared threshold-based empty close, and WorkerCommand append; both TaskTypes have deterministic Redis proof through the command mailbox and ITEM_DRIVEN proves no warmup/cache path | Recent-first Redis Task acquisition |
| Worker Delivery Dispatch | Shared Java Worker Delivery contract, Server point/batch HTTP API, complete multi-endpoint WebSocket/Socket Adapter instances with independent Netty listeners, bind-first long-lived connections, stateless bounded batch acquisition, fixed system-polling binding, and a Java 11 serial Polling/WebSocket/Socket Worker library | Authentication, same-endpoint Adapter HA, pending/ack, and production protocol policy |
| Result routing | Implemented with unit and Redis orchestration proof; Task/Worker policy handlers are replaceable; Java exposes bounded last-success reads | Failure/history projection and stronger queue reliability require separate owners and invariants |

Both TaskTypes also have cross-process Redis E2E proof from Java control and
Task data APIs through Python scheduling and the Java Server Worker Delivery
API. `TASK_DRIVEN` uses the Worker library's polling transport;
`ITEM_DRIVEN` uses independent Netty WebSocket/Socket Adapter endpoints and
the matching Worker transports. Tests install a local observable handler
rather than a framework-owned business handler. All paths converge through
Result-Routing, `FINAL_SUCCESS`, Java last-success query, and exact Worker
lease release. Java controllers use the same Kernel owner contracts as the
Python executable spec; Server assembly
chooses Python HTTP or Java Redis providers per operation.
Additional Redis proofs cover
TASK_DRIVEN default empty close, ITEM_DRIVEN future-threshold empty recheck
followed by append, shared explicit threshold close, and an external explicit
close request. A separate hard-deadline scanner remains deferred.

Deferred policy stays in the document of the mechanism that consumes it. There
is no global policy backlog document and no policy residue may create a second
runtime path.

## Current Scale Boundaries

The executable spec deliberately uses a small number of Redis owner keys:

```text
tr:{prefix}:task:score
  one global Task score ZSET

wr:{prefix}:score:{workerGroupId}
  one Worker score ZSET per WorkerGroup

tr:{prefix}:task:{taskId}:item-score
tr:{prefix}:task:{taskId}:items
tr:{prefix}:task:{taskId}:results
  Task-local Item score and record/result HASHes

wd:{prefix}:endpoint-manager:{endpointManagerId}:worker-commands
  one sparse WorkerCommand HASH per Adapter route

rr:{prefix}:worker-results:{outcomeClass}
  three global best-effort result LISTs
```

These are current mechanism boundaries, not claims of unlimited throughput.
Adding Pacer or HTTP threads does not partition a hot Redis key and may only
increase duplicate observation and exact-CAS contention. WorkerGroup, Task,
Adapter route, and outcome class are the existing natural batching boundaries.
A single very large WorkerGroup, one extremely hot Task, the global Task score
key, or one result-class LIST may require a future explicitly owned
partitioning design.

The polling API performs only point mailbox consume for one target Worker. It
never scans a bucket. Each locally registered Adapter instance may
consume one bounded random batch from its sparse bucket through the Server
batch HTTP API and
serve Workers through an independent Netty listener. The Adapter owns its
scheduler, command consume bound, current connection selection, bounded
delivery, `3001`/`UNKNOWN`, and result-buffer policy. The Server host only binds
configuration and forwards process lifecycle events.
Destructive prefetch failure remains `UNKNOWN` without pending/ack.

## Core Reading Path

After this index, a new agent can understand the current scheduling mainline
from five documents:

1. [Task Resource Model](../resource-model/task-resource-model.md)
2. [Assignment-Dispatch Scheduling](assignment-dispatch-scheduling.md)
3. [Worker HOT_ACQUIRE Lease Protocol](worker-hot-acquire-lease-protocol.md)
4. [Task Dispatch Pacer](task-dispatch-pacer.md)
5. [Result-Routing Scheduling](result-routing-scheduling.md)

Read owner details only when changing that owner:

- Score axes: [Task](task-score-band-scheduling.md),
  [Worker](worker-score-band-scheduling.md), and
  [TaskItem](task-item-score-band-scheduling.md).
- Admission and cache warming:
  [Task Running Activation](task-running-activation-pacer.md) and
  [Task-Worker Allocation](task-worker-allocation-pacer.md).
- Process and transport:
  [Kernel Application Assembly](../kernel-application-assembly.md),
  [Worker Delivery Dispatch](worker-delivery-dispatch.md), and
  [JVM Runtime API Server](../../../server_jvm/README.md).
- Backend representation:
  [Worker Runtime Redis Shape](../runtime-redis/worker-runtime-redis-shape.md)
  and [Worker Result Runtime Redis Shape](../runtime-redis/worker-result-runtime-redis-shape.md).

The score documents own encoding and transition rules. The Worker lease
protocol owns the one cross-pacer lease lifecycle without becoming a score or
runtime owner. The assignment parent owns the three-pacer protocol. Each pacer
document owns its round sequence, limits, stale/failure behavior, and deferred
policy. Redis shape documents own backend representation only.

## Executable Spec Map

```text
kernel_design/executable_spec/
  kernel/
    task_score_band.py
    worker_score.py
    task_item_score_band.py
    task_runtime.py
    worker_runtime.py
    assignment_dispatch_runtime.py
    result_context.py
    worker_result_runtime.py
    worker_delivery.py
  scheduling/
    worker_candidate/
      acquisition.py
      matching.py
    task_running_activation.py
    task_worker_allocation.py
    task_dispatch.py
    result_routing.py
  redis_runtime/
    task_score_band.py
    worker_score.py
    task_item_score_band.py
    task_runtime.py
    worker_runtime.py
    assignment_dispatch.py
    result_routing.py
    worker_delivery.py
  assembly/
    application.py
    assignment_dispatch_application.py
    result_routing_application.py
```

## Guardrails

- Do not merge Task, TaskItem, Worker, candidate-runtime, or result evidence
  truth to reduce the number of calls.
- Do not make an external event the only scheduling or recovery trigger.
- Do not let a pacer decode or mint another owner's opaque score evidence.
- Do not let Worker Delivery Dispatch select Workers or Result Routing refresh
  Task score.
- Do not make transient candidate or WorkerResult queues durable lifecycle truth.
- Do not add a hot-path index, scanner, lock, queue, or transaction without a
  named owner invariant and bounded cost.
- Do not preserve superseded mechanisms as historical alternatives in active
  documents.
