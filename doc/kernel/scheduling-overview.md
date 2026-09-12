# Kernel Scheduling Mainline

Status: current cross-owner scheduling flow and scale boundary.

The [Kernel index](README.md) links mechanical and policy contracts. This
page explains their handoffs; encoding, individual round limits and legal
transitions remain with the owning documents.

## Independent Scheduling Truth

Task, TaskItem and Worker Scores are independent, opaque scheduling
coordinates. They are neither resource write locks nor projections of network
state. The [Task Score](../../kernel_jvm/doc/score/task-score-band-scheduling.md)
owner controls Task scheduling visibility;
[TaskItem Score](../../kernel_jvm/doc/score/task-item-score-band-scheduling.md)
controls claim, retry and final outcome;
[Worker Score](../../kernel_jvm/doc/score/worker-score-band-scheduling.md)
controls acquisition, lease and serviceability eligibility.

## Dispatch Mainline

```text
Server creation
  -> Matching Task binding (explicit default or named Rule)
  -> Kernel Task descriptor; captured Item selector validated by Matching

Kernel Main Scheduler
  -> bounded due RUNNING observation -> INITIAL initialization
  -> NORMAL descriptors -> Task dispatch and optional Serviceability

Task dispatch
  -> one bounded Task binding read -> prepared Task queries
  -> due Items; TTL/exhaustion settlement
  -> eligible IDs -> HOT -> initial hold -> membership recheck
  -> exact Worker confirmation -> exact Item claim -> Command
  -> ACTIVE recheck before exact Task close or idle park
```

The Main Scheduler supplies every Producer's root Task/Group identities.
Producers discover only vertical resources under those inputs. Busy Producers
skip that snapshot; they do not accumulate a pending source queue. Assembly and
lifecycle are defined in
[Pacer Application Assembly](../../kernel_pacer_jvm/doc/application-assembly.md).

Matching owns paired facts projection and bounded query functions. Indexes are
shared derived eligibility, not Task jobs or scheduling truth. Kernel holds and
rechecks identities; exact confirmation rejects dirty/stale fences. Unselected
or rejected holds expire naturally. Kernel never reads Rule IDs, facts or index
coordinates. See [Assignment and Dispatch](../../kernel_pacer_jvm/doc/dispatch/assignment-dispatch-scheduling.md).

## Results And Recovery

```text
Worker/Adapter Result evidence -> Server validates and selects its owner
  TASK -> Kernel Result Policy -> TaskItem and Worker semantic events
  KERNEL -> Serviceability Result Policy in every preset -> Worker semantic events
  SERVER -> Server Direct Call waiter
  SYSTEM -> platform event destination; admitted Properties observations enter Matching
```

SUCCESS stores the Result projection before separately requesting Item success
finality and exact Worker release. Later outcome observations instead advance
the existing Item Score before conditionally storing content, without Worker
lease changes or Task reopening. Score and Result each retain their own maximum
target; higher tag wins, with time advancing within the tag. The Result Owner
retains millisecond precision while Score uses its existing time slots.
Retryable FAILURE releases the correlated
Worker lease without writing an Item Result or deciding finality. Task dispatch
stores a failed marker before promoting an exhausted/expired Item. These are
independent Owner operations: Result observation does not prove finality and
there is no unconditional Result-to-Score replay or repair guarantee.

The [Result Policy](../../kernel_pacer_jvm/doc/result/result-routing-scheduling.md)
owns parsing, grouping and semantic publication;
[Result storage](../../kernel_jvm/doc/runtime-redis/task-result-runtime-redis-shape.md)
owns the projection format and interruption windows. The
[HOT Lease Protocol](../../kernel_jvm/doc/score/worker-hot-acquire-lease-protocol.md)
owns the opaque fence across initial hold, assignment and release.

Optional [Serviceability](../../kernel_pacer_jvm/doc/dispatch/worker-serviceability-scheduling.md)
combines bounded demanded-Group probes and Adapter evidence. Dispatch advances
its exact observation before offering a probe; Result interpretation invokes
the dedicated time-fenced Score operation. Neither Server nor Transport moves
Score, and Task result evidence must not infer network polarity.

## Scale And Liveness

The production model is deliberately vertical:

```text
small bounded active Task set
  -> many TaskItems per Task
  -> many Workers inside finite WorkerGroups
```

Fully occupied compatible Workers that keep completing work are normal
backpressure. Bounded scans, exact CAS and bounded index take can add short
convergence delay. Persistently due work and persistently idle compatible
Workers failing to form assignments across repeated eligible rounds is a
liveness defect. A full Task page alone does not establish starvation.

Do not add Task rotation, tenant fairness or global Group discovery solely
because work waits behind fully utilized Workers. Massive active Task/Group
cardinality, multi-tenant isolation, sharding and fairness require a separate
architecture. Adding threads does not partition an Owner's hot Redis key.

## Boundaries And Proof

[Worker Delivery](worker-delivery-dispatch.md) starts with already-targeted
Commands. Events and hints may accelerate work but must not become correctness
prerequisites. Lease expiry restores resource eligibility after evidence loss;
it does not recreate a lost Result or repair separate finality writes.

Use [TESTING](../../TESTING.md) to select proof by claim. Focused policy tests
establish decisions, Redis Owner tests establish atomic fences, and Runtime
Boundary/system lanes establish their own finite cross-process relationships.
A passing layer does not substitute for another layer's evidence.
