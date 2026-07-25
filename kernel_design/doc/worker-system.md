# Worker System

Status: current Worker owner map; resource, scheduling, assignment, and
delivery mechanisms implemented in the Python executable spec; binding,
connectivity, and execution policy coverage partial.

## Purpose

The Worker System is a first-class Kernel subsystem alongside the Task System:

```text
Task System
  -> Task and TaskItem scheduling truth

Worker System
  -> Worker resource and scheduling-serviceability truth

Assignment Dispatch
  -> observes both systems
  -> selects one Worker slot
  -> claims one TaskItem

Worker Delivery Dispatch
  -> transports the completed assignment

Result Routing
  -> invokes TaskItem and Worker owners independently
```

Running these components in one `KernelApplication` or one Runtime Server does
not merge their owners. Worker scheduling is not a helper owned by Task
scheduling, and Task scheduling does not own Worker resource, score, route, or
connectivity truth.

The Worker System is not one large lifecycle state machine. Resource,
scheduling, binding, connectivity, and execution describe different facts with
different writers and consistency boundaries.

## Lifecycle Dimensions

| Dimension | Authoritative owner | Current Kernel representation | Current status |
| --- | --- | --- | --- |
| Resource | `WorkerResourceCatalog` and `WorkerRuntime` | WorkerGroup and Worker descriptors plus global WorkerId ownership | upsert implemented; disable/remove commands deferred |
| Scheduling | `WorkerScoreCore` | HOT_ACQUIRE or RECOVERY_RECHECK polarity, time coordinate, laneRank, and dirty fence | acquire/lease/release/reconnect/result disposition implemented; active recovery probe deferred |
| Binding | no independent owner yet | immutable `endpointManagerId` declaration copied into assignment evidence | bound route implemented; unbound/migrating workflow deferred |
| Connectivity | Adapter or session owner outside Kernel scheduling | endpoint/session observations may produce bounded evidence | system-polling protocol implemented; session truth and degraded-state policy deferred |
| Execution | assignment lease plus external Worker evidence | Worker lease, WorkerCommand, and SeedResult correlation | assignment/result fence implemented; executing ACK or started evidence deferred |

These dimensions must not be collapsed into a single `WorkerState` enum.
Transitions in one dimension may influence another only through an explicit
owner operation:

```text
reconnect evidence
  -> WorkerRuntime
  -> reconcile scheduling polarity and dirty fence

trusted Adapter rejection
  -> SeedResult
  -> Result Routing
  -> exact WorkerScoreCore demotion

successful Worker result
  -> SeedResult
  -> Result Routing
  -> exact WorkerScoreCore release
```

## Resource Dimension

The resource dimension owns durable declaration and identity facts:

```text
WorkerGroupDescriptor
WorkerDeclaration
WorkerDescriptor
WorkerId ownership
platform and declared attributes
dynamic attribute declarations
endpointManagerId declaration
```

The current command is an idempotent constrained upsert. It establishes a new
Worker or refreshes reconnect-owned attributes while preserving immutable
identity fields and platform attributes.

Resource existence is not Worker score truth. A future `disabled` or `removed`
command must define its own durable semantics and coordinate scheduling through
an explicit WorkerScoreCore operation. Do not implement resource mutation as a
generic score rewrite, and do not infer that removal means physical deletion
or WorkerId reuse before that contract is approved.

Canonical contract:
[Worker Resource Model](resource-model/worker-resource-model.md).

## Scheduling Dimension

Worker score is the scheduling-serviceability owner:

```text
HOT_ACQUIRE
  eligible for ordinary allocation when its time coordinate is due

RECOVERY_RECHECK
  excluded from ordinary allocation
  eligible only for an explicit recovery validation path
```

`leased` is not a third polarity or a separate persisted lifecycle enum. It is
a future HOT_ACQUIRE time coordinate protected by an opaque exact-score fence.
Likewise, hold, cooldown, drain, and maintenance may share a future coordinate;
their reason belongs to policy or evidence rather than score encoding.

Dirty protects active assignment continuation. It is not connectivity,
resource status, metadata version, or a global Worker lifecycle.

Canonical contracts:

1. [Worker Score-Band Scheduling](scheduling/worker-score-band-scheduling.md)
2. [Worker HOT_ACQUIRE Lease Protocol](scheduling/worker-hot-acquire-lease-protocol.md)

## Binding Dimension

The current executable spec supports one stable bound route:

```text
WorkerDescriptor.endpointManagerId
  -> CandidateWorkerEntry route snapshot
  -> endpoint-manager WorkerCommand mailbox
```

The route chooses the delivery bucket after Worker selection. It does not
participate in matching, prove connectivity, or become Result Routing truth.

There is no current `WorkerBindingRuntime`, unbound state, migration state, or
route epoch. Controlled migration needs a separate owner contract only when a
real scenario defines:

```text
who initiates migration
which active Worker leases block it
how a crash resumes or aborts it
what happens to commands in the old route bucket
which fence prevents stale route writes
```

Do not make ordinary reconnect upsert silently change the route.

## Connectivity Dimension

Connectivity belongs to the final-hop Adapter or session owner. Kernel
scheduling owns the resulting serviceability classification, not a real-time
socket mirror.

`polling` is an access profile, not a connectivity state. A polling Worker may
have no persistent connection and still remain scheduling-serviceable. A
long-lived Adapter may observe connected, disconnected, or degraded sessions,
but those observations remain evidence until an explicit Kernel owner
operation accepts them.

Current protocol boundaries:

```text
system-polling
  -> one target Worker point poll/result

long-lived Adapter
  -> one endpoint-manager cursor batch/result ingress
  -> owns sessions, push, flow control, and local reachability evidence
```

Canonical contract:
[Worker Delivery Dispatch](scheduling/worker-delivery-dispatch.md).

## Execution Dimension

One `WorkerId` is one scheduler-visible execution slot. The Kernel currently
knows:

```text
Worker lease acquired
Worker selected
TaskItem claimed
WorkerCommand published
SeedResult received or lease expired
```

It does not know exactly when Worker execution starts. Mailbox consumption,
HTTP response delivery, or Adapter buffering cannot prove `executing`.
Introducing authoritative `assigned / executing` states requires an explicit
Worker ACK or started-evidence protocol and its timeout/recovery owner.

Until that protocol exists:

- the Worker lease protects assignment occupancy;
- `WorkerCommand` is delivery evidence;
- `SeedResult` is result evidence;
- missing evidence converges through TaskItem claim and Worker lease expiry;
- external Worker effects remain at-least-once.

Do not create an attempt aggregate or execution-state store only for
observability.

## Code Placement

Worker is first-class through owner contracts, not through a second directory
taxonomy:

```text
executable_spec/kernel/worker_*.py
  -> Worker owner contracts and stable internal protocols

executable_spec/redis_runtime/worker_*.py
  -> Redis implementations of those owners

executable_spec/scheduling/worker_candidate/
  -> bounded Worker source and matching mechanisms

executable_spec/scheduling/worker_recovery.py
  -> future recovery orchestration, only when its policy is implemented

executable_spec/assembly/worker_scheduling_application.py
  -> future Worker background lifecycle, only when a real loop exists
```

Do not create an empty `worker_system` Python package, mirror existing
contracts into new DTOs, or move connectivity/session implementations into the
Kernel. Java Runtime Server, Adapter, SDK, and integration code may implement
external protocol and session concerns while Python remains the mechanism
oracle.

## Current Deferred Work

- Resource disable and remove semantics.
- Controlled Worker route migration.
- RECOVERY_RECHECK probe policy and background orchestration.
- Authenticated Adapter/session connectivity evidence.
- Explicit Worker execution ACK or started evidence.

Each item requires its own scenario, owner, transition, failure recovery, and
executable proof. Their absence does not create permission to combine the five
dimensions into one state machine.

## Reading Order

1. [Worker Resource Model](resource-model/worker-resource-model.md)
2. [Worker Score-Band Scheduling](scheduling/worker-score-band-scheduling.md)
3. [Worker HOT_ACQUIRE Lease Protocol](scheduling/worker-hot-acquire-lease-protocol.md)
4. [Assignment-Dispatch Scheduling](scheduling/assignment-dispatch-scheduling.md)
5. [Worker Delivery Dispatch](scheduling/worker-delivery-dispatch.md)
6. [Result-Routing Scheduling](scheduling/result-routing-scheduling.md)
7. [Worker Runtime Redis Shape](runtime-redis/worker-runtime-redis-shape.md)
