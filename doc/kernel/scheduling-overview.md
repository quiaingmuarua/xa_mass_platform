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
  -> Matching Candidate Rule first for PRECOMPUTED
     or Kernel normalization of an ON_DEMAND Worker Selector
  -> Kernel Task descriptor and TaskItem records

Kernel Main Scheduler
  -> one bounded due RUNNING Task score observation
  -> Owner-derived INITIAL subset -> initialization Producer
  -> NORMAL descriptors -> allocation, Task dispatch and optional serviceability

PRECOMPUTED allocation
  -> Kernel orders Candidate deficits and exact-holds a bounded due Worker pool
  -> WorkerMatchQueue admits the ordered Demand
  -> Matching reads supplied Rules/facts and writes accepted Candidate entries

Task dispatch
  -> observe due Items and classify TTL/exhaustion
  -> consume cached PRECOMPUTED candidates or acquire ON_DEMAND IDs/ANY
  -> exact Worker renewal -> exact Item claim -> targeted Command publication
  -> complete ACTIVE recheck before exact Task close or idle park
```

The Main Scheduler supplies every Producer's root Task/Group identities.
Producers discover only vertical resources under those inputs. Busy Producers
skip that snapshot; they do not accumulate a pending source queue. Assembly and
lifecycle are defined in
[Pacer Application Assembly](../../kernel_pacer_jvm/doc/application-assembly.md).

Matching owns facts and Rule interpretation, not priority, Score or assignment.
A Candidate carries bounded matching evidence and an opaque held score; final
renewal can reject it. Unmatched, unselected or rejected holds expire naturally
without a compensation-release registry. ON_DEMAND selectors contain only
normalized Worker IDs/ANY and neither interpret Properties nor fall back to
Candidate Cache. The detailed flow is
[Assignment and Dispatch](../../kernel_pacer_jvm/doc/dispatch/assignment-dispatch-scheduling.md).

## Results And Recovery

```text
Worker/Adapter Result evidence -> Server validates and selects its owner
  TASK -> Kernel Result Policy -> TaskItem and Worker semantic events
  KERNEL -> optional Serviceability Result Policy -> Worker semantic events
  SYSTEM -> Server Direct Call waiter
```

SUCCESS stores the Result projection before separately requesting Item success
finality and exact Worker release. Retryable FAILURE releases the correlated
Worker lease without writing an Item Result or deciding finality. Task dispatch
stores a failed marker before promoting an exhausted/expired Item. These are
independent Owner operations: Result observation does not prove finality and
there is no unconditional Result-to-Score replay or repair guarantee.

The [Result Policy](../../kernel_pacer_jvm/doc/result/result-routing-scheduling.md)
owns parsing, grouping and semantic publication;
[Result storage](../../kernel_jvm/doc/runtime-redis/task-result-runtime-redis-shape.md)
owns the projection format and interruption windows. The
[HOT Lease Protocol](../../kernel_jvm/doc/score/worker-hot-acquire-lease-protocol.md)
owns the opaque fence across allocation, assignment and release.

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
backpressure. Bounded scans, exact CAS and Candidate refill can add short
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
