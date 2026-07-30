# Kernel Core Design Workspace

Status: current clean-kernel mechanism workspace and Python executable
specification.

This directory is the semantic oracle for the clean kernel. The superseded Java
platform is preserved by `legacy-java-platform-final-2026-07-24` as historical
failure-mode evidence; it is not an architecture or compatibility target.

Treat this workspace as kernel-core design notes and Python executable specs
for a clean rewrite, not as a roadmap for incrementally repairing the current
codebase.

This is not a simplified-kernel exercise. It is a stricter clean-kernel redesign
after months of accumulated implementation complexity. Fewer interfaces mean
higher ownership pressure on each interface, not lower design quality.

Every interface should be able to explain:

```text
why it exists
who owns it
which mechanism it protects
which facts it refuses to carry
which callers or owners it refuses to replace
```

It is a place to keep target mechanisms, runtime memory models,
state-transition models, and owner-boundary design that are larger than one
module owner doc and broader than a single roadmap.

The goal is precise, consistent agent alignment before and during executable
spec changes. A kernel design note should make clear:

```text
what the target mechanism is
which owner owns which truth
which current implementation facts are only failure-mode references
which runtime structures and transitions are expected
which proof boundaries future executable specs must satisfy
```

## Current Mechanism Baseline

The declared scheduling baseline is mechanism-complete in the Python
executable spec:

```text
Task scheduling is the current Kernel control-flow core.
Worker resource and score remain independent truth owners invoked by
task-driven scheduling.
One Runtime Server or KernelApplication may host these owners without merging
their truth or mutation authority.
```

```text
Java Task Data ingress
  -> canonical TaskItem record + ACTIVE Item-score initialization
Task admission and score visibility
  -> TASK_DRIVEN or ITEM_DRIVEN scheduling profile
  -> Task Dispatch
     -> ACTIVE Item: Worker candidate acquisition -> TaskItem claim
                     -> WorkerCommand mailbox append
     -> no ACTIVE Item: increment the shared empty-recheck count
                        close only when emptyCloseAtMillis is due
  -> Worker Delivery Dispatch
     -> point polling for one target Worker
     -> bounded no-cursor batches for long-lived Adapters
     -> semantic Worker/Adapter WorkerResult ingress
     -> outcome-class WorkerResult queues
  -> Result Routing
     -> TaskItem and Worker truth convergence
  -> Java last-success result query
  -> kernel applies an explicit close requested by an external owner
```

The two public Task types are supported scheduling scenario contracts, not
independent caller knobs or arbitrary policy bundles. Their canonical contract
is defined in
[Task Resource Model](doc/resource-model/task-resource-model.md#task-type-and-allocation-rule).

Current work should improve owner-local policy, bounds, cadence, fairness,
recovery behavior, and operational adapters without inventing another
scheduling mainline. A new kernel mechanism requires a named correctness or
liveness invariant that the existing score axes, owner operations, and bounded
handoffs cannot protect. Production transport reliability, query projections,
and control-plane concerns are not evidence that scheduling needs another
owner or state machine.

### Scenario Before Policy

Kernel policy exists only inside a supported scenario:

```text
concrete workload scenario
  -> TaskType contract
  -> scenario invariants
  -> necessary policy decisions
  -> kernel owner mechanisms
  -> vertical executable proof
```

Do not start with orthogonal cache, acquisition, trigger, termination,
fairness, or retry policies and then search their Cartesian product for
unsupported combinations. Such combinations do not establish a product
scenario and usually create validation branches without protecting a real
invariant.

Adding a TaskType requires a concrete workload that the existing types cannot
support without changing rule ownership, Worker acquisition, cache authority,
or another scheduling invariant. Different close thresholds, limits, cadence,
priority, fairness, or retry values remain System Policy inside an existing
TaskType. Acceptance is a vertical scenario proof, not policy-combination
coverage.

TaskType is not a priority or transport-style label. `TASK_DRIVEN` and
`ITEM_DRIVEN` may each be used for RPC-style or batch-oriented work and may
each receive dense or sparse Items. Their stable distinction is whether the
Task or each TaskItem owns the complete Worker rule and whether Task-level
candidate evidence is reusable.

## Core Axioms

The kernel core should stay small:

```text
kernel_core moves between logical states
owners maintain truth
policies decide mapping
transports carry evidence
```

Scheduling mechanisms should be mostly score-band state transitions, bounded
claim/result mutations, and invariant checks. Do not introduce production-style
framework layers, bridge modules, CRUD owners, attempt aggregates, or lifecycle
facades just to make the design look complete.

Small kernel does not mean reduced mechanism quality. The standard is:

```text
v0 can have narrow policy coverage
v0 must not use the wrong mechanism to get a quick loop
mechanism boundaries must stay owner-correct
truth, evidence, policy, transport, query, and diagnostics must stay separate
unsupported policy features are explicit omissions, not design shortcuts
```

Examples:

```text
limited DSL operators are acceptable; making eventCode a worker-match field is not
simple retry policy is acceptable; hiding TaskItem retry truth in Task score is not
in-process handoff is acceptable; merging owner truth is not
bounded fallback is acceptable; event-only liveness is not
```

The kernel is score-band scheduled, not event triggered:

```text
score-bands are the scheduling clock
score-bands decide bounded acquire / recheck opportunities
owners validate truth before moving state
policies map evidence to the next score
external events only provide business evidence or optional acceleration
```

### Three Score Axes

The new kernel uses three independent score axes with one shared mechanism:

```text
one scheduling identity -> one ZSET member -> one ordered score coordinate
```

The score is the only scheduling truth for that identity. It is not the
complete resource object and does not absorb descriptors, payload, result
reason, transport connection, trace, or query projection truth.

| Axis | Scheduling identity | Encoded direction | Time rule | Final/recovery rule |
| --- | --- | --- | --- | --- |
| Task | global `taskId` | positive lifecycle tag decreases | same-band `timeSlot` normally increases | negative score is immutable terminal |
| Worker | `workerId` inside one home bucket / WorkerGroup | sign is kernel-owned TaskItem scheduling serviceability: positive HOT_ACQUIRE, negative RECOVERY_RECHECK | same-polarity `timeSlot` normally increases | polarity may toggle because Worker is long-lived; there is no terminal band |
| TaskItem | `(taskId, messageId)` | outcome tag increases | ACTIVE same-band `timeSlot` increases | outcome precedence advances toward final success; there is no release |

One WorkerId is one scheduler-visible execution slot and therefore one active
Worker lease at a time. Physical executor concurrency is represented by
multiple logical WorkerIds. One TaskItem remains one scheduling, claim, retry,
and result unit. A business batch is one TaskItem whose payload contains a
bounded collection; the kernel does not coalesce multiple TaskItems.

The shared contract is:

```text
bounded range + limit discovers due identities
public owner methods accept millisecond time, not internal timeSlot
timeSlot is the next time the current lane may act
suffix / low-order fields carry only lane-local ordering or state
priority and rank ranges are lane-local, with smaller values ordered first
the score owner mints encoded scores and Redis ranges
score values crossing an owner boundary are opaque observation / lease fences
business meaning stays above the score primitive
the score owner still enforces field width, monotonic direction, suffix rules,
and stale-write safety
score absence is not a hidden parked, paused, terminal, or scheduling-unavailable state
```

Ordinary time movement is monotonic. A lower time coordinate is allowed only
through a declared exact-fence exception such as Task/Worker release or Worker
recovery exhaustion to an owner-minted cold coordinate. TaskItem deliberately
has no release: claim or retry remains held until its future coordinate becomes
due.

Concurrency strength is operation-specific. Monotonic range minting is enough
when a stale writer can only move the coordinate safely forward. Exact
observed-score comparison is required when old evidence could overwrite a
newer lease, consume suffix/budget, release a hold, or change Worker polarity.
TaskItem cross-tag outcome promotion intentionally uses numeric precedence
instead of a claim fence.

Current executable-spec implementations use 100ms slots while all owner-facing
time inputs remain milliseconds. Slot resolution and score packing are private,
but changing either against persisted Redis data is an encoding/keyspace
migration, not an in-place reinterpretation of existing scores.

## Score Mutation Authority

Score is a scheduling coordinate, not a resource mutation lock. Ordinary owner
truth must remain writable without reading, acquiring, leasing, or rewriting a
task or worker score:

```text
task descriptor / allocation metadata update
Worker platform / declared / dynamic attribute update
task item append
Item result / transport evidence write
read projection / trace materialization
```

Those writes may affect facts read by a later scheduling round, but they do not
become score transitions and must not wait for score ownership. In particular,
TaskRuntime append owns canonical TaskItem persistence and invokes
TaskItemScoreBandCore for initial Item-score creation. It does not make a Task
schedulable, refresh Task score, or emit a required wakeup.

The current external process realization is intentionally split by operation
behind one owner contract. Java `RedisTaskRuntime` implements public TaskItem
append and last-success reads against the same Redis shapes; Python
`RedisTaskRuntime` remains the executable-spec oracle and is used internally by
scheduling and ResultRouting. `server_jvm` assembles the provider per operation
without defining another runtime interface. This is one Task data truth, not
mirrored storage or a fallback path.

Append acceptance is deliberately narrow:

```text
append accepted
  = TaskRuntime stored the latest TaskItem record
  + TaskItemScoreBandCore initialized ACTIVE Item score or confirmed that the
    messageId scheduling identity already exists

append accepted
  != task is live or schedulable
  != dispatcher will consume the item
  != item is guaranteed to produce a result
  != terminal Task must reopen
```

Ingress/product policy, currently expected at the server boundary, decides
whether a caller may append to a closed or intake-disabled Task. It may use a
bounded status cache, close-append tag, tombstone, or another business-owned
gate. Small observation delay is acceptable: a late item accepted after Task
terminal is invalid residue, not a kernel lifecycle failure. Task score remains
terminal and the item must not recreate scheduling visibility.

Physical cleanup races are retention concerns. If terminal cleanup removes Item
keys and a delayed append recreates them, retention may use TTL, repeated
cleanup, generation-scoped keys, or an ingress tombstone. The kernel must not
solve this by making every append read or lease Task score.

```text
ingress owner decides append eligibility
TaskRuntime owns TaskItem persistence
TaskItemScoreBandCore owns Item-score initialization and movement
Task score owns Task scheduling visibility
dispatcher owns consumption opportunity
retention owner removes terminal residue
```

Score write authority is deliberately narrow:

```text
initialization owner
  establishes the first score during task creation or first Worker upsert

scheduling plane
  is the only routine writer while a score is in an acquirable scheduling lane
  owns acquire classification, next time, retry/budget consumption, hold, and
  ordinary same-lane rewrite

lifecycle command owner
  may perform only explicit control transitions such as approve, reject,
  pause, resume, cancel, close, serviceability demotion, or validated recovery
```

The lifecycle-command exception does not create a second scheduling mainline.
It cannot calculate ordinary retry cadence, consume scheduling-round budget, or
perform generic score refresh. It validates command-owner truth and invokes one
declared transition primitive protected by current-score validation or an exact
observed-score fence.

Worker dirty is also not a resource-update lease. A worker attribute update
commits independently. Only when a real persisted assignment continuation
depends on the changed field may the owner additionally attempt a bounded dirty
mark. Dirty failure cannot reject or roll back the attribute update.

The hard owner rule is:

```text
resource owners write resource truth without a score lease
TaskRuntime appends TaskItem through TaskItemScoreBandCore initialization
scheduling owns routine acquirable-score evolution
command owners own explicit lifecycle transitions only
score never becomes a global mutation lock
```

The kernel protects scheduling invariants, not every product-level acceptance
promise. It must not absorb business fallback logic merely to guarantee that
every accepted item is eventually consumed.

The kernel must close its own loop without ordinary external events:

```text
task score-band recheck
worker score-band acquire / admission
assignment-dispatch bounded claim
result classification -> TaskItemScoreBandCore transition
Item score lease expiry / retry-budget exhaustion
terminal / policy closure
```

## Event Cost And Correctness Contract

External event emission is high-cost and opt-in. The default kernel design is
no event emission for ordinary observations or high-frequency mutations.

```text
event emission is high-cost
event emission must be explicitly justified
event emission must fast-fail
event emission is allowed only for key owner state changes
external events must not be required for correctness
external events must not be required for automatic liveness
external events must not be the only state-transition trigger
external events may only accelerate scheduling that already has a fallback
```

The kernel must still converge when ordinary external observations are not
emitted, or when an explicitly allowed event is lost, duplicated, delayed, or
delivered out of order.

Allowed event emission must satisfy all of these constraints:

```text
owner-local state has already changed
the change is a key state transition, not routine evidence
the event carries evidence only, not truth
the event only shortens scheduling / recheck latency
the scheduler has a non-event fallback path
the emit path is bounded and fast-fail
emit failure cannot roll back or block the owner state transition
there is a cheaper score/recheck/repair path for correctness and liveness
```

Every event-accelerated path must still be reachable through owner-local
scheduling:

```text
score scan
score-state recheck
current-truth validation
bounded repair
policy timeout / closure
```

Forbidden event shapes:

```text
per-append wakeup fanout
per-heartbeat scheduling wakeup
per-result global reschedule
unbounded event queue as scheduling backlog
retrying emit until success inside the owner mutation
event-only scheduling with no score/recheck fallback
```

These shapes create avalanche risk: a burst of routine observations can create
more scheduling pressure than the scheduler itself can bound. Event delivery
may be dropped, coalesced, sampled, or rate-limited without changing kernel
correctness.

The current append path uses the allowed bounded form: Server coalesces by
taskId in a capacity-limited buffer, Python coalesces again in a private inbox,
and Task Dispatch may exact-release only an existing future empty-recheck hold.
The hint may be dropped and never participates in append acceptance or
scheduling liveness.

Routine observations and high-frequency writes must not emit by default:

```text
append
heartbeat / keepalive
transport ack
read-model update
trace materialization
generic dirty marker
```

The hard rule is:

```text
Events may report that truth moved, but only owners may move truth.
```

Explicit human gates are the exception because they intentionally suspend
automatic liveness:

```text
manual review
approval / rejection
manual resume / cancel
operator-required unresolved handling
```

These are not ordinary evidence events. They are authoritative commands whose
owner transaction still validates the current state before changing truth.

## Design Standard

Design notes describe the production-grade target design, not the current
implementation shape.

The current codebase may be useful as:

```text
failure-mode evidence
invariant inventory
proof-gap inventory
migration-cost input
anti-pattern examples
```

It must not lower the kernel design standard. If the current implementation is
confusing, inefficient, or owner-mixed, the design note should say so indirectly
by defining the cleaner target owner split and runtime mechanism. Do not
normalize current design debt into the target architecture just because it is
what exists today.

### Interface Contract Standard

Kernel interfaces are design artifacts, not conveniences shaped around the
current implementation. Once caller, owner, inputs, output, side effects, and
concurrency semantics have been aligned, that contract is frozen until an
explicit interface decision changes it.

Before adding or changing a kernel-facing method, record this contract:

| Question | Required answer |
| --- | --- |
| Owner | Which owner is allowed to perform the operation? |
| Caller | Which scheduling plane or owner invokes it? |
| Inputs | Which values can the caller construct, validate, and legitimately own? |
| Output | Which fact or bounded evidence does the result represent? |
| Side effects | Which owner truth, score, lease, queue, or key may change? |
| Bounds | What limits scans, batches, retries, or memory growth? |
| Concurrency | Is the operation best-effort, monotonic, lease-guarded, or exact CAS? |
| Refusals | Which adjacent policy or owner responsibility must remain outside? |

The implementation must follow these rules:

```text
caller-owned bounded input stays an explicit input
callee-owned internal truth stays hidden
policy chooses bounds and mapping
mechanism validates and performs only its declared mutation
removing a helper or wrapper preserves the existing owner split
test convenience does not justify a new interface or owner
```

#### Conservative Owner Contracts

A rich policy surface does not require a broad owner interface. System and
Task policies may compose many scheduling decisions while Kernel owners expose
only the narrow operations needed to preserve their truth.

The default owner contract is:

```text
owner-local
explicit caller-supplied identities
bounded by the caller
aggregated within one owner key when the storage shape supports it
free of hidden discovery, fan-out, and background coordination
```

For example, a TaskRuntime result read may accept one `taskId` plus a bounded
set of `messageIds` and read that Task's result HASH. Grouping requests across
multiple Tasks, choosing when to retry them, and coordinating their completion
belong to the calling policy or application. That orchestration does not become
a Kernel contract merely because a pipeline could make it convenient.

Cross-key fan-out, global discovery, owner-spanning aggregation, new queues or
threads, and stronger consistency are high-cost contract choices. Before adding
one, the design must name:

```text
the production invariant that requires it
why existing owner-local operations cannot express it
the worst-case work and the bound that contains it
the owner, key, failure, and partial-success semantics
the cheaper policy-layer alternative that was rejected
the focused proof that locks the new boundary
```

Without that evidence, keep the operation in system/Task policy orchestration
and retain the conservative owner contract. Mechanism extensibility and
external interface conservatism are complementary constraints.

Reducing argument count is not inherently an improvement. A stable value such
as `workerIds`, selected and bounded by the caller, is a valid contract input.
Moving its acquisition into a matcher would add hidden I/O and move scheduling
policy into the matching mechanism even if the new signature looked smaller.

Likewise, deleting a pass-through helper means inlining its existing sequence:

```text
before:
  helper = owner A operation + owner B call

after:
  caller performs owner A operation, then calls owner B directly
```

It does not authorize changing owner B's signature, moving owner A's operation
into owner B, adding a facade, or changing side effects. Hidden I/O is a
contract change even when the method signature is unchanged.

The following require explicit agreement before code changes:

- adding or removing a public/kernel-facing parameter or return field;
- moving score acquisition, descriptor lookup, queue access, lease mutation,
  policy evaluation, or persistence across an owner boundary;
- changing bounded input into discovery, or discovery into caller input;
- changing best-effort behavior into atomic/CAS behavior or the reverse;
- adding a bridge, facade, wrapper, registry, callback, background loop, or
  second runtime path;
- changing which component owns limits, ordering, retries, or fairness.

If a request has two plausible interpretations and one changes any item above,
stop and compare the alternatives before implementation. Do not choose a new
contract merely because it makes the current function shorter or the tests
easier to satisfy.

Executable-spec proof must cover both behavior and boundary shape:

```text
signature/DTO shape is locked by a contract test
the expected owner performs each external read or mutation
the callee does not discover inputs that the caller must supply
no removed wrapper survives under a new name
no hidden compatibility path or second mainline remains
```

## Current Design Notes

- [Kernel Design Documents](doc/README.md)
  - routing index for scheduling, resource-model, and runtime-Redis notes.
- [Kernel Core Scheduling](doc/scheduling/README.md)
  - four scheduling planes for the new kernel core: task score-band, worker
    score-band, assignment-dispatch, and result-routing.
- [Worker Resource Model](doc/resource-model/worker-resource-model.md)
  - v0 metadata/query projection for worker groups, workers, and dynamic
    attribute allowlists.
- [Worker HOT_ACQUIRE Lease Protocol](doc/scheduling/worker-hot-acquire-lease-protocol.md)
  - canonical allocation lease, dispatch exact recheck, result-driven
    release/recovery classification, reconnect dirty fence, and natural expiry.
- [Task Resource Model](doc/resource-model/task-resource-model.md)
  - v0 Task allocation metadata, Task scheduling priority, allocation-rule
    routing, Item retry policy, and bounded descriptor reads.
- [Task Item Score-Band Scheduling](doc/scheduling/task-item-score-band-scheduling.md)
  - one canonical TaskItem from append through finality, with TaskItem record, ACTIVE
    claim/retry, and monotonic result outcome movement; no second Work model.
- [Assignment-Dispatch Scheduling](doc/scheduling/assignment-dispatch-scheduling.md)
  - plane contract for independent admission, allocation, and Item-dispatch
    pacing.
- [Task Running Activation Pacer](doc/scheduling/task-running-activation-pacer.md)
  - ADMISSION Task/System policy chain and the sole default
    transition into RUNNING.
- [Task-Worker Allocation Pacer](doc/scheduling/task-worker-allocation-pacer.md)
  - hint-driven TASK_DRIVEN candidate warming, bounded HOT Worker
    lease/match, and cache publication; Task score is read only for current
    RUNNING/non-pause/suffix-zero validation.
- [Task Dispatch Pacer](doc/scheduling/task-dispatch-pacer.md)
  - candidate consumption, Item score claim, dispatch-time Worker lease
    validation/renewal, WorkerCommand construction, and Adapter-partitioned
    WorkerCommand mailbox append.
- [Kernel Application Assembly](doc/kernel-application-assembly.md)
  - independent resource upsert and scheduling-process boundaries,
    private Redis composition, background scheduling lifecycles, built-in CLI,
    and the executable-spec Kernel Runtime HTTP host.
- [Worker Delivery Dispatch](doc/scheduling/worker-delivery-dispatch.md)
  - Server-owned point/batch mailbox access plus Adapter-owned complete Netty
    instances, independent Command/Result loops, direct WorkerCommand/
    WorkerResult transport, bounded queues, and trusted pre-execution rejection
    without Adapter-owned score mutation.
- [Kernel Runtime Server](runtime_server/app.py)
  - executable-spec FastAPI control host for WorkerGroup/Worker upsert and
    Task create/approve/close; only
    `KernelApplication` owns background lifecycle.
- [JVM Runtime API Server](../server_jvm/README.md)
  - external control proxy, Java TaskItem append/last-success query, point
    polling and Adapter batch HTTP API; its owner-scoped Redis access is
    limited to TaskData and Worker Delivery operations.
- [JVM Worker Delivery Adapter](../transport/netty-adapter/README.md)
  - complete Adapter instance registration, independent Netty WebSocket
    and Socket listeners, start/close lifecycle, bounded mailbox dispatch,
    active connections, and Server batch HTTP client.
- [Worker Core](../transport/worker-core/README.md)
  - Java 11 compatible Worker execution, event dispatch, and
    Polling/WebSocket/Socket protocol state machines shared by JVM and Android
    hosts.
- [JVM Worker Clients](../transport/okhttp-worker/README.md)
  - concrete OkHttp point/WebSocket and JDK line-socket clients for Worker
    Core.
- [Android Worker Client](../transport/android-client/README.md)
  - Android HandlerThread/Looper OkHttp WebSocket client for explicit host
    composition with Worker Core.
- [JVM Worker Delivery Contract](../worker_delivery_contract_jvm/README.md)
  - transport-neutral Java command/result/bind DTOs, strict validation,
    outcome classification, and codec.
- [Result-Routing Scheduling](doc/scheduling/result-routing-scheduling.md)
  - outcome-class WorkerResult consumption, last-success result storage, TaskItem
    final-success invocation, and Worker exact disposition.
- [Worker Result Runtime Redis Shape](doc/runtime-redis/worker-result-runtime-redis-shape.md)
  - three best-effort outcome-class queues plus Task-scoped success result HASH.

## Boundary

Design notes define the intended owner contract; executable-spec code and tests
prove the currently implemented mechanism. If they disagree, identify the drift
before changing either one.

The Python executable spec lives under `kernel_design/executable_spec/`.
`kernel_design/runtime_server/` is its Kernel Control FastAPI host.
`transport/worker-core/` contains the external Worker execution and protocol
state machines. `transport/okhttp-worker/` and `transport/android-client/`
supply concrete network clients; none is a Kernel owner. The Kotlin production
scaffold lives under `kernel_jvm/` and may
implement behavior only through scoped parity slices against this workspace.
Historical tag material must not constrain current interfaces, Redis shapes,
or package boundaries.

## Agent Rules

- Read the relevant design note before drafting a new-kernel executable-spec
  plan that changes the same owner plane.
- Do not treat a design note as implementation evidence.
- Do not constrain a design note to current engine/module shape unless the
  constraint is a deliberate production requirement.
- Do not implement directly from a design note without a scoped executable-spec
  plan or explicit owner decision when the change crosses owners, runtime
  truth, storage shape, or a kernel-facing interface.
- If code reality diverges from the design note, report the gap; do not silently
  bend the design note into current behavior.
- If a future executable spec proves part of a design note and makes it current
  new-kernel truth, migrate that fact to the owning kernel-core documentation
  and leave the design note as target context only.
