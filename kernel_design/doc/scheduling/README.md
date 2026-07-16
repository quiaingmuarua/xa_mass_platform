# Kernel Core Scheduling

Status: active new-kernel mechanism index; Python executable spec implemented;
policy coverage partial.

The workspace-level isolation, event-cost, liveness, and score-writer rules are
defined in [Kernel Core Design Workspace](../../README.md). This file is the
entry map for scheduling owners and their current executable mechanisms.

## Scheduling Planes

The kernel has four scheduling planes:

| Plane | Owner question | Input | Output | Truth it may mutate |
| --- | --- | --- | --- | --- |
| Task score-band | Which Tasks may enter a scheduling round now? | Task score coordinate and owner facts | Bounded Task ids or an owner-approved score transition | Task scheduling score only |
| Worker score-band | Which Workers may enter admission or recovery now? | Worker score coordinate and worker-runtime facts | Bounded Worker observations, leases, holds, or polarity movement | Worker scheduling score only |
| Assignment-dispatch | Which bounded Task/Worker/Item combination becomes delivery evidence? | Due Tasks, Worker observations, Task descriptors, candidate queues, TaskItem records and Item scores | CandidateWorker entries and queued DeliverSeeds | Owner-local candidate runtime and TaskItem score through declared primitives |
| Result-routing | How does returned evidence affect Item finality/retry and Worker hold? | SeedResults and opaque result context | Item outcome/retry request and Worker exact-release request | No private truth; invokes TaskItem and Worker score owners |

These are logical planes, not mandatory deployment modules. Package placement
may change without changing owner authority.

`TaskItemScoreBandCore` is a separate score owner used by assignment-dispatch
and result-routing. It is not collapsed into Task score and is not a fifth
process lifecycle.

All three score owners follow the shared
[Three Score Axes](../../README.md#three-score-axes) contract:

```text
Task      lifecycle moves to lower tags; negative is immutable terminal
Worker    sign is online/offline polarity; abs(score) carries scheduling time
TaskItem  outcome moves to higher tags; ACTIVE claim time only moves forward
```

They share ordered discovery, owner-minted coordinates, opaque score fences,
and bounded numeric transition primitives. They do not share one tag direction,
one suffix meaning, or one universal delta constant. Each axis derives the
simplest safe numeric relation from its own encoding; Redis/Lua does not need
business event names or caller-supplied score ranges.

## Mainline

```text
Task score acquire
  -> Worker allocation and exact Worker lease
  -> candidate-worker runtime
  -> exact Worker lease validate/renew
  -> TaskItem score acquire / claim
  -> DeliverSeed queue
  -> endpoint-manager delivery
  -> SeedResult queue
  -> result routing classification
     -> 200 / 1xxx: TaskItem outcome/retry + Worker exact release
     -> 3xxx: TaskItem retry + Worker exact offline transition
     -> no result: Item claim and Worker lease expire naturally
```

The score axes and bounded runtime handoffs provide liveness. Events may provide
business evidence or reduce latency, but they are not required scheduling
triggers. Detailed mutation authority remains in the owner documents below.

## Owner Boundaries

```text
TaskScoreBandCore
  owns Task acquisition visibility, encoding, bounded queries, holds,
  lifecycle-direction validation, and terminal score

TaskRuntime
  owns Task descriptors and canonical TaskItem records

TaskItemScoreBandCore
  owns Item initialization, bounded ACTIVE acquisition, observed same-tag
  claim/retry rewrites, and monotonic final outcome promotion

WorkerScoreCore and worker-runtime
  own Worker acquisition/recovery coordinates, exact leases, dirty fence,
  resource metadata, dynamic attributes, and candidate validation

AssignmentDispatchRuntime
  owns transient per-Task candidate-worker queues and per-endpoint DeliverSeed
  queues; neither queue is lifecycle truth

SeedResultRuntime
  owns one bounded best-effort SeedResult queue, not result classification

Scheduling pacers
  compose owner operations in bounded rounds; they do not copy owner truth

Transport adapters
  consume already-assigned DeliverSeeds and emit SeedResults; they do not
  select Workers or mutate score
```

## Mechanism Status

| Mechanism | Executable status | Owner-local deferred policy |
| --- | --- | --- |
| Task score-band | Implemented with Redis proof | Cadence, scan horizons, activation and no-work budget values |
| Worker score-band | Implemented with Redis proof, including dirty lease fence | Dirty marking policy when a persisted assignment continuation exists; recovery cadence and ranking |
| Worker HOT_ACQUIRE lease protocol | Allocation, dispatch exact recheck, result release/offline, and reconnect dirty fence implemented | Recovery probe cadence and future explicit capacity-owner policy |
| TaskItem score-band | Implemented with Redis proof | Initial retry budget and retry delay values |
| Worker allocation | Implemented with unit and Redis orchestration proof | PRE_DISPATCH/RUNNING weighting or quota beyond current RUNNING-first behavior |
| Task running activation | Implemented | Alternative activation policies beyond the built-in minimum candidate count |
| TaskItem dispatch | Implemented through DeliverSeed append | Recent-first Redis Task acquisition remains deferred |
| Outbound delivery example | Independent clients and Local Function Adapter implemented | Production transport, pending/ack, and protocol-specific conversion |
| Result routing | Implemented with unit and Redis orchestration proof | Result projection and stronger queue reliability require separate owners and invariants |

Deferred policy stays in the document of the mechanism that consumes it. There
is no global policy backlog document and no policy residue may create a second
runtime path.

## Reading Order

1. [Task Score-Band Scheduling](task-score-band-scheduling.md)
2. [Worker Score-Band Scheduling](worker-score-band-scheduling.md)
3. [Worker HOT_ACQUIRE Lease Protocol](worker-hot-acquire-lease-protocol.md)
4. [Task Item Score-Band Scheduling](task-item-score-band-scheduling.md)
5. [Assignment-Dispatch Scheduling](assignment-dispatch-scheduling.md)
6. [Task-Worker Allocation Pacer](task-worker-allocation-pacer.md)
7. [Task Item Dispatch Pacer](task-item-dispatch-pacer.md)
8. [DeliverSeed Outbound Delivery](deliver-seed-outbound-delivery.md)
9. [Result-Routing Scheduling](result-routing-scheduling.md)
10. [Kernel Application Assembly](../kernel-application-assembly.md)
11. [Worker Runtime Redis Shape](../runtime-redis/worker-runtime-redis-shape.md)
12. [Seed Result Runtime Redis Shape](../runtime-redis/seed-result-runtime-redis-shape.md)
13. [Local Function Transport Adapter](../../examples/local_function_adapter/README.md)

The score documents own encoding and transition rules. The Worker lease
protocol owns the one cross-pacer lease lifecycle without becoming a score or
runtime owner. The assignment parent owns the two-pacer protocol. Each pacer
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
    seed_result_runtime.py
  scheduling/
    worker_candidate_matcher.py
    task_worker_allocation.py
    task_item_dispatch.py
    result_routing.py
  redis_runtime/
    task_score_band.py
    worker_score.py
    task_item_score_band.py
    task_runtime.py
    worker_runtime.py
    assignment_dispatch.py
    result_routing.py
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
- Do not let transport select Workers or result-routing refresh Task score.
- Do not make transient candidate or SeedResult queues durable lifecycle truth.
- Do not add a hot-path index, scanner, lock, queue, or transaction without a
  named owner invariant and bounded cost.
- Do not preserve superseded mechanisms as historical alternatives in active
  documents.
